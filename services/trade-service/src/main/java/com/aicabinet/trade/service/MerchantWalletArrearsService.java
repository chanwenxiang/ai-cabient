package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.WalletAccountMapper;
import com.aicabinet.trade.support.ScheduleZones;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 商户钱包欠款账龄探针（CB-086，2026-11-13）。
 *
 * <p><b>只读，零资金动作</b>：不改余额、不改提现闸门、不碰柜机锁售。余额可为负本身是既有拍板
 * （佣金/分账冲正挂负、押金直减 —— 见 {@code MerchantWalletService#debitIfAbsentAllowNegative}），
 * 与 PayPal「入账自动抵扣欠款」、有赞/Amazon「负数待扣」同族；缺的是「负了多久、要不要人管」，
 * 本类只补这一层的可见性。</p>
 *
 * <p><b>为什么不能按余额判欠费</b>：{@code MerchantDailyIncomeNotifyScheduler#checkOverdueAndRemind}
 * 的 7 日锁柜判据刻意只看 {@code merchant_fee_ledger} 的 PENDING（审计 T3：押金负扣/佣金冲正挂负
 * 也会让余额为负，按余额判会把商户全部柜机误锁）。本探针同样<b>不触发任何锁柜</b>，只把结果
 * 汇入日结任务的执行记录并推动一条运营群告警。</p>
 *
 * <p><b>账龄起算</b>：取账本里「最后一次 {@code balance_after >= 0}」的时刻（账本 append-only），
 * 即欠款连续段的起点。整本账从未非负 ⇒ 最早一行；无流水 ⇒ 不可判，跳过（不当作 0 天）。</p>
 */
@Service
public class MerchantWalletArrearsService {

    /** 与 {@code WalletAccount.domain} 一致；线长/员工钱包不属本探针范围。 */
    static final String DOMAIN_MERCHANT = "MERCHANT";

    /** 账龄阈值（天），0=关闭。竞品锚：PayPal 120 天锁号/催收、有赞-Amazon 90 天追溯、Stripe 180 天平台兜底。 */
    static final String CONFIG_KEY = "merchant.wallet_arrears.alert_after_days";

    /** 默认取 90：与本仓佣金冲正「90 天追溯窗」同一把尺（账本口径自洽优先于抄某一家的数字）。 */
    static final int DEFAULT_ALERT_AFTER_DAYS = 90;

    static final String ALERT_TYPE = "MERCHANT_WALLET_ARREARS";

    private static final Logger log = LoggerFactory.getLogger(MerchantWalletArrearsService.class);

    private final WalletAccountMapper accountMapper;
    private final SystemConfigService configService;
    private final OpsAlertDispatcher opsAlertDispatcher;

    public MerchantWalletArrearsService(WalletAccountMapper accountMapper,
                                        SystemConfigService configService,
                                        OpsAlertDispatcher opsAlertDispatcher) {
        this.accountMapper = accountMapper;
        this.configService = configService;
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    /**
     * 汇总本轮「欠款满账龄」的商户：返回可直接拼进日结任务执行记录的一句摘要（无命中返回空串），
     * 并在有命中时推一条聚合群告警（每个日结周期最多一条，任务本身每日一次）。
     */
    public String dailySummary(LocalDate today) {
        int thresholdDays = thresholdDays();
        if (thresholdDays <= 0) {
            return "";
        }
        List<Map<String, Object>> rows = accountMapper.findNegativeArrears(DOMAIN_MERCHANT);
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        long arrearsTotalCents = 0L;
        long maxAgeDays = 0L;
        int hitCount = 0;
        int unjudgeable = 0;
        StringBuilder longest = new StringBuilder();
        for (Map<String, Object> row : rows) {
            Long balanceCents = asLong(row.get("c1"));
            LocalDate since = asBusinessDate(row.get("c2"));
            if (balanceCents == null || balanceCents >= 0) {
                continue;
            }
            if (since == null) {
                // 无流水 ⇒ 账龄不可判。宁可漏报也不按 0 天误报（首日就催是假警报）。
                unjudgeable++;
                continue;
            }
            long ageDays = ChronoUnit.DAYS.between(since, today);
            if (ageDays < thresholdDays) {
                continue;
            }
            hitCount++;
            arrearsTotalCents += -balanceCents;
            if (ageDays > maxAgeDays) {
                maxAgeDays = ageDays;
                longest.setLength(0);
                longest.append(row.get("c0"));
            }
        }
        if (hitCount == 0) {
            if (unjudgeable > 0) {
                log.info("merchant wallet arrears: {} negative account(s) skipped (no ledger history)", unjudgeable);
            }
            return "";
        }
        String amount = BigDecimal.valueOf(arrearsTotalCents, 2).toPlainString();
        String summary = "；另有 " + hitCount + " 商户钱包欠款满 " + thresholdDays + " 天（合计 "
                + amount + " 元，最长账龄 " + maxAgeDays + " 天，入账自动抵扣；不改柜机状态）";
        log.warn("merchant wallet arrears over threshold: count={} totalCents={} maxAgeDays={} oldest={}",
                hitCount, arrearsTotalCents, maxAgeDays, longest);
        try {
            opsAlertDispatcher.send(ALERT_TYPE, "商户钱包欠款超账龄",
                    "欠款满 " + thresholdDays + " 天的商户 " + hitCount + " 家，合计 " + amount
                            + " 元，最长账龄 " + maxAgeDays + " 天（" + longest + "）。"
                            + "处置口径：入账自动抵扣 + 提现闸门阻断；本告警只读，不锁柜。"
                            + "阈值 sys_config " + CONFIG_KEY);
        } catch (Exception e) {
            // 告警通道故障不得影响日结主流程（与其他资金告警同口径）
            log.warn("merchant wallet arrears alert failed: {}", e.getMessage());
        }
        return summary;
    }

    /** 阈值（天）；非法/缺失回落默认 90，0/负数=关闭。 */
    int thresholdDays() {
        try {
            String raw = configService.getValue(CONFIG_KEY, String.valueOf(DEFAULT_ALERT_AFTER_DAYS));
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_ALERT_AFTER_DAYS;
        }
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    /** 账龄起算点按业务时区折算成自然日；无流水 ⇒ null（不可判）。 */
    private static LocalDate asBusinessDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().atZone(ScheduleZones.ZONE).toLocalDate();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(ScheduleZones.ZONE).toLocalDate();
        }
        if (v instanceof Instant instant) {
            return instant.atZone(ScheduleZones.ZONE).toLocalDate();
        }
        if (v instanceof java.time.LocalDate ld) {
            return ld;
        }
        return null;
    }
}
