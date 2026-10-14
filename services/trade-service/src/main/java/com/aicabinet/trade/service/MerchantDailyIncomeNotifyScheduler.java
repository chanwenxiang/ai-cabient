package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantFeeLedgerMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.support.ScheduleZones;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户日结通知（T+1，对齐老系统「收入结算通知」，2026-10-14 用户拍板）。
 *
 * <p>每日 00:20（LineCommissionJob 同口径错峰）汇总「昨日」各商户入账
 * （{@code order_revenue_split.merchant_cents} 按创建日聚合，冲正单 VOIDED/REVERSED
 * 不计——口径与 {@link MerchantSettlementBillService} 一致），发给**昨日有收入
 * （合计 &gt;0）的商户**；0.00 不发（用户拍板，与老系统全量推送有意不同）。
 *
 * <p>渠道：站内信（merchant-mp 消息中心）。三字段对齐老系统：
 * 商户名称 / 结算周期（昨日日期）/ 收入金额。模板 {@code merchant_settlement}（V351）。
 *
 * <p>幂等：{@code biz_id=昨日日期}，发送前按 template_code+merchant_id+biz_type+biz_id
 * 查重，调度重跑不重发。本项目收益是「下单即入钱包、商户自行提现」——本通知只是
 * 昨日入账汇总的告知，不涉及任何打款动作。
 *
 * <p>费用日摊与欠费检查覆盖全部有柜商户（CB-041：费用摊销与销售脱钩，零销售照摊），
 * 不限于昨日有收入商户；0.00 仍不发日结通知（用户拍板）。
 */
@Component
public class MerchantDailyIncomeNotifyScheduler {
    private static final Logger log = LoggerFactory.getLogger(MerchantDailyIncomeNotifyScheduler.class);
    private static final String PROCESS = "merchant-daily-income-notify";
    private static final String TEMPLATE_CODE = "merchant_settlement";
    private static final String BIZ_TYPE = "DAILY_INCOME";
    static final String FEE_OVERDUE_LOCK_PREFIX = "费用欠缴锁柜";
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MerchantMapper merchantRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final NotificationService notificationService;
    private final NotificationLogMapper notificationLogRepository;
    private final ScheduledTaskService taskService;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantFeeLedgerMapper feeLedgerRepository;
    private final MerchantWalletService walletService;
    private final SystemConfigService configService;
    private final DeviceSalesLockService salesLockService;

    public MerchantDailyIncomeNotifyScheduler(MerchantMapper merchantRepository,
                                              OrderRevenueSplitMapper splitRepository,
                                              NotificationService notificationService,
                                              NotificationLogMapper notificationLogRepository,
                                              ScheduledTaskService taskService,
                                              DeviceInfoMapper deviceRepository,
                                              MerchantFeeLedgerMapper feeLedgerRepository,
                                              MerchantWalletService walletService,
                                              SystemConfigService configService,
                                              DeviceSalesLockService salesLockService) {
        this.merchantRepository = merchantRepository;
        this.splitRepository = splitRepository;
        this.notificationService = notificationService;
        this.notificationLogRepository = notificationLogRepository;
        this.taskService = taskService;
        this.deviceRepository = deviceRepository;
        this.feeLedgerRepository = feeLedgerRepository;
        this.walletService = walletService;
        this.configService = configService;
        this.salesLockService = salesLockService;
    }

    @Scheduled(
            cron = "${aicabinet.daily-income-notify.scheduled-cron:0 20 0 * * *}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void notifyYesterdayIncome() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(PROCESS, 900)) {
            return;
        }
        boolean failed = false;
        String summary = "日结通知未执行";
        try {
            LocalDate yesterday = LocalDate.now(ScheduleZones.ZONE).minusDays(1);
            int sent = sendDailyIncomeNotify(yesterday);
            summary = "昨日(" + yesterday + ")日结通知发送 " + sent + " 商户";
            log.info("scheduled daily income notify completed: {}", summary);
        } catch (Exception e) {
            failed = true;
            taskService.finish(PROCESS, "FAILED", e.getMessage(), start);
            log.error("scheduled daily income notify failed", e);
        } finally {
            if (!failed) {
                taskService.finish(PROCESS, "SUCCESS", summary, start);
            }
        }
    }

    /** 发送昨日日结通知，返回实际发送数。包级可见供单测钉住口径。 */
    int sendDailyIncomeNotify(LocalDate yesterday) {
        Instant from = yesterday.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant to = yesterday.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        List<OrderRevenueSplit> splits = splitRepository.selectList(Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, from)
                .lt(OrderRevenueSplit::getCreatedAt, to));
        Map<String, Long> incomeByMerchant = aggregateIncome(splits);
        if (incomeByMerchant.isEmpty()) {
            return 0;
        }
        String bizId = yesterday.format(ISO_FMT);
        int sent = 0;
        for (Map.Entry<String, Long> entry : incomeByMerchant.entrySet()) {
            String merchantId = entry.getKey();
            if (alreadySent(merchantId, bizId)) {
                continue;
            }
            String merchantName = merchantRepository.findById(merchantId)
                    .map(Merchant::getMerchantName).orElse(merchantId);
            // 周期费用日摊（用户拍板「净额展示+每日扣余额」）：租金+流量费按月额/当月天数摊销，
            // 每日从商户余额扣（幂等 refId=昨日日期）；余额可负（欠费自然累计，老系统同款直减）。
            long feeCents;
            try {
                feeCents = chargeDailyPeriodFees(merchantId, yesterday);
            } catch (Exception e) {
                // 🔴 单商户扣费失败不阻断全量（其余商户照常通知），失败商户次日重跑补扣
                log.warn("daily fee charge failed for {}: {}", merchantId, e.getMessage());
                feeCents = 0L;
            }
            long incomeCents = entry.getValue();
            long netCents = incomeCents - feeCents;
            notificationService.notifyMerchant(merchantId, TEMPLATE_CODE,
                    Map.of("merchantName", merchantName,
                            "period", yesterday.format(PERIOD_FMT),
                            "amount", BigDecimal.valueOf(incomeCents, 2).toPlainString(),
                            "fee", BigDecimal.valueOf(feeCents, 2).toPlainString(),
                            "net", BigDecimal.valueOf(netCents, 2).toPlainString()),
                    BIZ_TYPE, bizId);
            sent++;
        }
        // CB-041（2026-10-14 拍板：费用摊销与销售脱钩——固定租金是合同义务，零销售照摊；
        // 免费投放本来费用为 0 不受影响）：日摊与欠费检查覆盖**全部有柜商户**，
        // 不再只扫昨日有收入商户（否则零销售商户费用永远不出账、欠费永远不催缴）。
        chargeAndCheckAllMerchants(yesterday, incomeByMerchant.keySet());
        return sent;
    }

    /**
     * 全商户费用日摊 + 欠费检查（CB-041）。商户集合 = 昨日有收入商户 ∪ 名下有柜机的商户
     * （欠费源于柜机费用，故按设备表枚举；费用为 0 的商户 chargeDailyPeriodFees 快速返回，
     * 余额为负仍会走催缴）。income 商户在上面循环里已扣过费——幂等（refId=日期）重复调用跳过。
     */
    void chargeAndCheckAllMerchants(LocalDate yesterday, java.util.Set<String> incomeMerchantIds) {
        java.util.Set<String> merchantIds = new java.util.LinkedHashSet<>(incomeMerchantIds);
        for (DeviceInfo d : deviceRepository.selectList(Wrappers.<DeviceInfo>lambdaQuery())) {
            String mid = d.getMerchantId();
            if (mid != null && !mid.isBlank()) {
                merchantIds.add(mid);
            }
        }
        for (String merchantId : merchantIds) {
            try {
                chargeDailyPeriodFees(merchantId, yesterday);
            } catch (Exception e) {
                // 🔴 单商户失败不阻断全量（其余商户照常出账），失败商户次日重跑补扣
                log.warn("daily fee charge failed for {}: {}", merchantId, e.getMessage());
            }
        }
        checkOverdueAndRemind(yesterday, merchantIds);
    }


    /**
     * 欠费处置（CB-004 追缴路径：先催 → 锁柜；2026-10-14 拍板，线下结算口径）：
     * ① 有未结清费用（PENDING 累计 &gt; 0）→ 当日发一条催缴信（bizId=日期 查重，每日最多一条）；
     * ② 未结清累计 ≥ 月费用总额 × lockMonths（默认 1）→ 名下未锁柜机按 FEE_OVERDUE 前缀锁柜；
     * ③ 全部结清（PENDING 归零，线下收款后标记）→ 名下因费用欠缴被锁的柜机自动解锁。
     * 锁柜阈值倍数可配：sys_config merchant.overdue.lock_months（默认 1）。
     */
    void checkOverdueAndRemind(LocalDate yesterday, java.util.Set<String> merchantIds) {
        String bizId = yesterday.format(ISO_FMT);
        int lockMonths;
        try {
            lockMonths = Math.max(1, Integer.parseInt(
                    configService.getValue("merchant.overdue.lock_months", "1").trim()));
        } catch (NumberFormatException e) {
            lockMonths = 1;
        }
        for (String merchantId : merchantIds) {
            MerchantWalletAccount account = walletService.ensureAccount(merchantId);
            long balance = account.getBalanceCents() == null ? 0L : account.getBalanceCents();
            if (balance >= 0) {
                unlockFeeOverdue(merchantId);
                continue;
            }
            long debt = -balance;
            if (alreadySentType(merchantId, "FEE_REMINDER", bizId)) {
                // 当日已催缴
            } else {
                String merchantName = merchantRepository.findById(merchantId)
                        .map(Merchant::getMerchantName).orElse(merchantId);
                notificationService.notifyMerchant(merchantId, "merchant_fee_reminder",
                        Map.of("merchantName", merchantName,
                                "reminder", "您的平台费用已欠缴（余额为负），请及时充值",
                                "debt", BigDecimal.valueOf(debt, 2).toPlainString()),
                        "FEE_REMINDER", bizId);
            }
            long threshold = monthlyFeeTotal(merchantId) * lockMonths;
            if (threshold > 0 && debt >= threshold) {
                lockFeeOverdue(merchantId, debt);
            }
        }
    }

    private void lockFeeOverdue(String merchantId, long debt) {
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
        for (DeviceInfo d : devices) {
            if (!Boolean.TRUE.equals(d.getSalesLocked())) {
                salesLockService.applySalesLock(null, d, true,
                        FEE_OVERDUE_LOCK_PREFIX + "：欠费 " + BigDecimal.valueOf(debt, 2).toPlainString() + " 元",
                        true);
            }
        }
    }

    private void unlockFeeOverdue(String merchantId) {
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
        for (DeviceInfo d : devices) {
            if (Boolean.TRUE.equals(d.getSalesLocked())
                    && d.getSalesLockReason() != null
                    && d.getSalesLockReason().startsWith(FEE_OVERDUE_LOCK_PREFIX)) {
                salesLockService.applySalesLock(null, d, false, "欠费已结清", true);
            }
        }
    }

    /**
     * 周期费用日摊扣款：该商户名下柜机的 (租金 rentCents + 流量费 dataFeeCents) 月额
     * ÷ 当月天数 = 当日应摊，从商户钱包扣（entryType=DAILY_FEE，refType=SETTLEMENT_FEE，
     * refId=昨日日期——幂等，重跑不重扣）。余额不足直减为负（欠费累计，老系统同款）。
     * 返回当日实扣费用（分）。
     */
    long chargeDailyPeriodFees(String merchantId, LocalDate yesterday) {
        long monthlyTotal = monthlyFeeTotal(merchantId);
        if (monthlyTotal <= 0L) {
            return 0L;
        }
        int daysInMonth = yesterday.lengthOfMonth();
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
        long rentDaily = 0L, dataDaily = 0L;
        for (DeviceInfo d : devices) {
            if (!"RENTAL".equalsIgnoreCase(d.getCoopMode()) || d.getRentCents() == null) {
                continue;
            }
            rentDaily += d.getRentCents() / daysInMonth;
            if (d.getDataFeeCents() != null) {
                dataDaily += d.getDataFeeCents() / daysInMonth;
            }
        }
        upsertFeeLedger(merchantId, yesterday, "REN", rentDaily);
        upsertFeeLedger(merchantId, yesterday, "DATA", dataDaily);
        long dailyFee = rentDaily + dataDaily;
        // 🔴 最终口径（2026-10-14 用户拍板对齐老系统）：余额**真扣**费用——「不从他们卡里扣，
        // 但余额肯定是减掉费用后的」，否则商户每天提现提走的是没扣费用的钱。余额可负=欠费。
        // 幂等扣款（refId=昨日日期，任务重跑不重扣；余额不足直减为负=欠费挂账）。
        if (dailyFee > 0) {
            walletService.debitIfAbsentAllowNegative(merchantId, dailyFee, "DAILY_FEE",
                    "SETTLEMENT_FEE", yesterday.format(ISO_FMT),
                    "周期费用日摊(租金+流量费/" + daysInMonth + "天)");
        }
        return dailyFee;
    }

    private void upsertFeeLedger(String merchantId, LocalDate feeDate, String feeType, long amountCents) {
        Long exists = feeLedgerRepository.selectCount(Wrappers.<MerchantFeeLedger>lambdaQuery()
                .eq(MerchantFeeLedger::getMerchantId, merchantId)
                .eq(MerchantFeeLedger::getFeeDate, feeDate)
                .eq(MerchantFeeLedger::getFeeType, feeType));
        if (exists != null && exists > 0) {
            return;
        }
        if (amountCents <= 0L) {
            return;
        }
        MerchantFeeLedger row = new MerchantFeeLedger();
        row.setMerchantId(merchantId);
        row.setFeeDate(feeDate);
        row.setFeeType(feeType);
        row.setAmountCents(amountCents);
        row.setStatus("PENDING");
        feeLedgerRepository.insert(row);
    }

    /** 该商户未结清费用累计（分）：催缴与锁柜阈值口径。 */
    long pendingFeeTotal(String merchantId) {
        List<MerchantFeeLedger> rows = feeLedgerRepository.selectList(
                Wrappers.<MerchantFeeLedger>lambdaQuery()
                        .eq(MerchantFeeLedger::getMerchantId, merchantId)
                        .eq(MerchantFeeLedger::getStatus, "PENDING"));
        return rows.stream().mapToLong(r -> r.getAmountCents() == null ? 0L : r.getAmountCents()).sum();
    }

    /** 该商户名下柜机的周期费用月额合计（租金仅 coopMode=RENTAL 计 + 流量费）。 */
    long monthlyFeeTotal(String merchantId) {
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
        long monthlyTotal = 0L;
        for (DeviceInfo d : devices) {
            long rent = "RENTAL".equalsIgnoreCase(d.getCoopMode()) && d.getRentCents() != null
                    ? d.getRentCents() : 0L;
            long dataFee = d.getDataFeeCents() == null ? 0L : d.getDataFeeCents();
            monthlyTotal += rent + dataFee;
        }
        return monthlyTotal;
    }

    /**
     * 纯函数：按商户聚合昨日入账（分），只保留 &gt;0 的商户（0.00 不发，用户拍板）。
     * 冲正单排除（口径对齐 {@link MerchantSettlementBillService#isReversed}）。
     */
    static Map<String, Long> aggregateIncome(List<OrderRevenueSplit> splits) {
        Map<String, Long> byMerchant = new HashMap<>();
        for (OrderRevenueSplit s : splits) {
            String merchantId = s.getMerchantId();
            if (merchantId == null || merchantId.isBlank()
                    || MerchantSettlementBillService.isReversed(s.getStatus())) {
                continue;
            }
            byMerchant.merge(merchantId, Math.max(0L, s.getMerchantCents()), Long::sum);
        }
        byMerchant.values().removeIf(v -> v <= 0);
        return byMerchant;
    }

    private boolean alreadySentType(String merchantId, String bizType, String bizId) {
        // 催缴信模板是 merchant_fee_reminder，与日结通知（merchant_settlement）不同——
        // 此前错用 TEMPLATE_CODE 查重永不命中（2026-10-14 审计 🟡4 修复）
        String templateCode = "FEE_REMINDER".equals(bizType) ? "merchant_fee_reminder" : TEMPLATE_CODE;
        Long c = notificationLogRepository.selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getTemplateCode, templateCode)
                .eq(NotificationLog::getAudience, "MERCHANT")
                .eq(NotificationLog::getMerchantId, merchantId)
                .eq(NotificationLog::getBizType, bizType)
                .eq(NotificationLog::getBizId, bizId));
        return c != null && c > 0;
    }

    private boolean alreadySent(String merchantId, String bizId) {
        Long c = notificationLogRepository.selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getTemplateCode, TEMPLATE_CODE)
                .eq(NotificationLog::getAudience, "MERCHANT")
                .eq(NotificationLog::getMerchantId, merchantId)
                .eq(NotificationLog::getBizType, BIZ_TYPE)
                .eq(NotificationLog::getBizId, bizId));
        return c != null && c > 0;
    }
}
