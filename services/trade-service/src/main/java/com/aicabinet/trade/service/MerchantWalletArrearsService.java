package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.WalletAccountMapper;
import com.aicabinet.trade.support.ScheduleZones;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 商户钱包欠款账龄探针（CB-086，2026-11-13）。
 *
 * <p><b>本类自身不写任何资金数据</b>：只读账本回答「谁的余额为负、负了多久」。
 * 余额可为负是既有拍板（佣金/分账冲正挂负、押金直减 —— 见
 * {@code MerchantWalletService#debitIfAbsentAllowNegative}），与 PayPal「入账自动抵扣欠款」、
 * 有赞/Amazon「负数待扣」同族；本类补的是「负了多久、要不要人管」。锁柜动作由
 * {@code MerchantDailyIncomeNotifyScheduler#enforceWalletArrears} 执行（复用费用欠费锁的
 * 同一把 {@code DeviceSalesLockService}，但用独立原因前缀、互不代 unlock）。</p>
 *
 * <p><b>为什么不能按余额判欠费</b>：{@code checkOverdueAndRemind} 的 7 日锁柜判据刻意只看
 * {@code merchant_fee_ledger} 的 PENDING（审计 T3：押金负扣/佣金冲正挂负也会让余额为负，
 * 按余额判会把商户全部柜机误锁）。本探针的锁柜判据是「<b>连续</b>负余额满账龄」，
 * 且与费用锁分开拥有设备锁：费用未清的设备不会被我方锁改写/解锁。</p>
 *
 * <p><b>账龄起算</b>：取账本里「最后一次 {@code balance_after >= 0}」的时刻（账本 append-only），
 * 即欠款连续段的起点。整本账从未非负 ⇒ 最早一行；无流水 ⇒ 不可判，跳过（不当作 0 天，也不锁柜）。</p>
 */
@Service
public class MerchantWalletArrearsService {

    /** 与 {@code WalletAccount.domain} 一致；线长/员工钱包不属本探针范围。 */
    static final String DOMAIN_MERCHANT = "MERCHANT";

    /** 告警阈值（天），0=关闭。竞品锚：PayPal 120 天锁号/催收、有赞-Amazon 90 天追溯、Stripe 180 天平台兜底。 */
    static final String CONFIG_KEY = "merchant.wallet_arrears.alert_after_days";

    /** 锁柜阈值（天），0=关闭锁柜（告警可独立保留）。 */
    static final String LOCK_CONFIG_KEY = "merchant.wallet_arrears.lock_after_days";

    /** 默认取 90：与本仓佣金冲正「90 天追溯窗」同一把尺（账本口径自洽优先于抄某一家的数字）。 */
    static final int DEFAULT_ALERT_AFTER_DAYS = 90;

    static final int DEFAULT_LOCK_AFTER_DAYS = 90;

    static final String ALERT_TYPE = "MERCHANT_WALLET_ARREARS";

    private static final Logger log = LoggerFactory.getLogger(MerchantWalletArrearsService.class);

    /** 一条「欠款满账龄」命中。 */
    public record Arrears(String merchantId, long arrearsCents, long ageDays, LocalDate since) {}

    private record Scan(List<Arrears> hits, int unjudgeable) {}

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
     * 汇总本轮「欠款满告警阈值」的商户：返回可直接拼进日结任务执行记录的一句摘要（无命中返回空串），
     * 并在有命中时推一条聚合群告警（每个日结周期最多一条，任务本身每日一次）。
     */
    public String dailySummary(LocalDate today) {
        int thresholdDays = alertThresholdDays();
        if (thresholdDays <= 0) {
            return "";
        }
        Scan scan = scan(null, today, thresholdDays);
        if (scan.hits().isEmpty()) {
            if (scan.unjudgeable() > 0) {
                log.info("merchant wallet arrears: {} negative account(s) skipped (no ledger history)",
                        scan.unjudgeable());
            }
            return "";
        }
        long arrearsTotalCents = 0L;
        long maxAgeDays = 0L;
        String oldest = "";
        for (Arrears hit : scan.hits()) {
            arrearsTotalCents += hit.arrearsCents();
            if (hit.ageDays() > maxAgeDays) {
                maxAgeDays = hit.ageDays();
                oldest = hit.merchantId();
            }
        }
        String amount = BigDecimal.valueOf(arrearsTotalCents, 2).toPlainString();
        String summary = "；另有 " + scan.hits().size() + " 商户钱包欠款满 " + thresholdDays + " 天（合计 "
                + amount + " 元，最长账龄 " + maxAgeDays + " 天，入账自动抵扣；不改柜机状态）";
        log.warn("merchant wallet arrears over threshold: count={} totalCents={} maxAgeDays={} oldest={}",
                scan.hits().size(), arrearsTotalCents, maxAgeDays, oldest);
        try {
            opsAlertDispatcher.send(ALERT_TYPE, "商户钱包欠款超账龄",
                    "欠款满 " + thresholdDays + " 天的商户 " + scan.hits().size() + " 家，合计 " + amount
                            + " 元，最长账龄 " + maxAgeDays + " 天（" + oldest + "）。"
                            + "处置口径：入账自动抵扣 + 提现闸门阻断"
                            + (lockThresholdDays() > 0
                                ? "；连续满 " + lockThresholdDays() + " 天会锁柜，结清自动复机"
                                : "")
                            + "。阈值 sys_config " + CONFIG_KEY + " / " + LOCK_CONFIG_KEY);
        } catch (Exception e) {
            // 告警通道故障不得影响日结主流程（与其他资金告警同口径）
            log.warn("merchant wallet arrears alert failed: {}", e.getMessage());
        }
        return summary;
    }

    /**
     * 全部负余额账户（含「账龄不可判」的行：{@code since=null}、{@code ageDays=-1}）。
     * 运营台欠款名单与 CSV 导出用它——欠款人不能因为账本缺流水就从名单里消失；
     * 告警/锁柜另走 {@link #overThreshold}，那里会剔除不可判行。
     */
    public List<Arrears> negativeAccounts() {
        LocalDate today = LocalDate.now(ScheduleZones.ZONE);
        List<Map<String, Object>> rows = accountMapper.findNegativeArrears(DOMAIN_MERCHANT, null);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Arrears> all = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long balanceCents = asLong(row.get("c1"));
            if (balanceCents == null || balanceCents >= 0) {
                continue;
            }
            LocalDate since = asBusinessDate(row.get("c2"));
            long ageDays = since == null ? -1 : ChronoUnit.DAYS.between(since, today);
            all.add(new Arrears(String.valueOf(row.get("c0")), -balanceCents, ageDays, since));
        }
        return all;
    }

    /** 命中清单（账龄 ≥ {@code minAgeDays}，已排除「账龄不可判」）。调用方传业务日，便于判据测试。 */
    public List<Arrears> overThreshold(long minAgeDays, LocalDate today) {
        return scan(null, today, (int) minAgeDays).hits();
    }

    /**
     * 单商户的欠款快照（余额为负才算命中；账龄不可判时返回空）。
     * 商户钱包列表用它补「账龄」列，按条查询（列表里负余额应是个位数）。
     */
    public java.util.Optional<Arrears> arrearsOf(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return java.util.Optional.empty();
        }
        List<Arrears> hits = scan(merchantId, LocalDate.now(ScheduleZones.ZONE), 0).hits();
        return hits.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(hits.get(0));
    }

    /**
     * 锁柜判据：该商户钱包是否「连续负余额 ≥ 锁柜阈值」。
     * 锁柜关闭（0）或无流水（不可判）一律 false —— 锁柜必须 fail-open 到「不锁」，
     * 因为误锁的代价是整店停售（审计 T3）。
     */
    public boolean inArrears(String merchantId) {
        int lockDays = lockThresholdDays();
        if (lockDays <= 0 || merchantId == null || merchantId.isBlank()) {
            return false;
        }
        return !scan(merchantId, LocalDate.now(ScheduleZones.ZONE), lockDays).hits().isEmpty();
    }

    /** 告警阈值（天）；非法/缺失回落默认 90，0/负数=关闭。 */
    int alertThresholdDays() {
        return readDays(CONFIG_KEY, DEFAULT_ALERT_AFTER_DAYS);
    }

    /** 锁柜阈值（天）；非法/缺失回落默认 90，0/负数=关闭锁柜（告警不受影响）。 */
    int lockThresholdDays() {
        return readDays(LOCK_CONFIG_KEY, DEFAULT_LOCK_AFTER_DAYS);
    }

    private int readDays(String key, int fallback) {
        try {
            String raw = configService.getValue(key, String.valueOf(fallback));
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Scan scan(String ownerId, LocalDate today, int minAgeDays) {
        List<Map<String, Object>> rows = accountMapper.findNegativeArrears(DOMAIN_MERCHANT, ownerId);
        if (rows == null || rows.isEmpty()) {
            return new Scan(List.of(), 0);
        }
        List<Arrears> hits = new ArrayList<>();
        int unjudgeable = 0;
        for (Map<String, Object> row : rows) {
            Long balanceCents = asLong(row.get("c1"));
            LocalDate since = asBusinessDate(row.get("c2"));
            if (balanceCents == null || balanceCents >= 0) {
                continue;
            }
            if (since == null) {
                // 无流水 ⇒ 账龄不可判。宁可漏报也不按 0 天误报（首日就催/就锁是假警报）。
                unjudgeable++;
                continue;
            }
            long ageDays = ChronoUnit.DAYS.between(since, today);
            if (ageDays < minAgeDays) {
                continue;
            }
            hits.add(new Arrears(String.valueOf(row.get("c0")), -balanceCents, ageDays, since));
        }
        return new Scan(hits, unjudgeable);
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
