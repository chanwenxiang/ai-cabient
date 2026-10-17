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
 * <p>费用日扣与欠费检查覆盖全部有柜商户（CB-041：费用扣收与销售脱钩，零销售照扣；
 * 2026-10-16 F-2/F-3/F-4 拍板：扣款成功即写 SETTLED 行、流量费分/天全柜机照收、
 * 漏扣由运营台按日期手动重跑），不限于昨日有收入商户；0.00 仍不发日结通知（用户拍板）。
 * <p>费用为 0 / 未配置是合法状态（不收 / 没配卡）：不扣钱、不写行、不计欠、不锁柜。
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
    /** 日扣的钱包流水 refType（refId=费用日期）：{@link #chargeDailyPeriodFees} 与通知取数共用同一把钥匙。 */
    static final String FEE_REF_TYPE = "SETTLEMENT_FEE";

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
            NotifyOutcome outcome = sendDailyIncomeNotify(yesterday);
            summary = "昨日(" + yesterday + ")日结通知发送 " + outcome.sent() + " 商户"
                    // F-5（2026-10-17）：扣费失败不再只在日志里静默——写进运营台执行记录，
                    // 否则商户收到的通知里 fee=0.00 没人知道那是「没扣成」而不是「免费」
                    + (outcome.feeChargeFailed() > 0
                        ? "；另有 " + outcome.feeChargeFailed() + " 商户日扣失败（当日未扣到费，"
                            + "通知里费用按实扣显示为 0，需运营台「按日期重跑日扣」补）"
                        : "");
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

    /**
     * 日结通知结果：{@code sent}=实际发送数，{@code feeChargeFailed}=昨日日扣失败商户数
     * （F-5：失败数必须冒泡到运营台执行记录，不许只在日志里静默）。
     */
    record NotifyOutcome(int sent, int feeChargeFailed) {
    }

    /** 发送昨日日结通知。包级可见供单测钉住口径。 */
    NotifyOutcome sendDailyIncomeNotify(LocalDate yesterday) {
        Instant from = yesterday.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant to = yesterday.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        List<OrderRevenueSplit> splits = splitRepository.selectList(Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, from)
                .lt(OrderRevenueSplit::getCreatedAt, to));
        Map<String, Long> incomeByMerchant = aggregateIncome(splits);
        if (incomeByMerchant.isEmpty()) {
            // 审计 T4（2026-10-15）：early-return 只对「通知发送」生效——费用日扣与欠费检查
            // （chargeAndCheckAllMerchants，CB-041）必须照跑，否则全员零销售的日子整批
            // 费用不出账、欠费不催缴、误锁不解锁（固定租金是合同义务，与销售脱钩）。
            chargeAndCheckAllMerchants(yesterday, java.util.Set.of());
            return new NotifyOutcome(0, 0);
        }
        String bizId = yesterday.format(ISO_FMT);
        int sent = 0;
        int feeChargeFailed = 0;
        for (Map.Entry<String, Long> entry : incomeByMerchant.entrySet()) {
            String merchantId = entry.getKey();
            if (alreadySent(merchantId, bizId)) {
                continue;
            }
            String merchantName = merchantRepository.findById(merchantId)
                    .map(Merchant::getMerchantName).orElse(merchantId);
            // 周期费用日扣（2026-10-16 拍板 F-2=A/F-3=A）：租金按月额/当月天数、流量费按分/天，
            // 每日从商户余额扣（幂等 refId=昨日日期）；余额可负（欠费自然累计，老系统同款直减）。
            try {
                chargeDailyPeriodFees(merchantId, yesterday);
            } catch (Exception e) {
                // 🔴 单商户扣费失败不阻断全量（其余商户照常通知）。失败=当日既没扣钱也没写费用行
                //（老系统 L5「先扣到钱才写行」），**不会自动补扣**，需运营台按日期手动重跑。
                log.warn("daily fee charge failed for {}: {}", merchantId, e.getMessage());
                feeChargeFailed++;
            }
            // F-5（2026-10-17）：通知里的费用取**钱包当日实扣流水**，不再用重算值——
            // 「重算值」在两种情况下与商户余额不符：① 扣款成功但随后写台账抛异常（钱已扣、通知说 0）；
            // ② 当天已由运营重跑扣过（幂等跳过，重算值再展示一遍=同一笔费用展示两次）。
            // 流水是唯一真相：扣到多少展示多少，没扣到就是 0（配合 summary 里的失败数不静默）。
            long feeCents = walletService.debitedCentsByRef(merchantId, FEE_REF_TYPE, bizId);
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
        // 免费投放本来费用为 0 不受影响）：日扣与欠费检查覆盖**全部有柜商户**，
        // 不再只扫昨日有收入商户（否则零销售商户费用永远不出账、欠费永远不催缴）。
        chargeAndCheckAllMerchants(yesterday, incomeByMerchant.keySet());
        return new NotifyOutcome(sent, feeChargeFailed);
    }

    /**
     * 全商户费用日扣 + 欠费检查（CB-041）。商户集合 = 昨日有收入商户 ∪ 名下有柜机的商户
     * （欠费源于柜机费用，故按设备表枚举；费用全为 0/未配置的商户不扣钱也不写行，
     * 余额为负仍会走催缴）。income 商户在上面循环里已扣过费——幂等（refId=日期）重复调用跳过。
     *
     * @return 本轮实扣费用合计（分）
     */
    long chargeAndCheckAllMerchants(LocalDate yesterday, java.util.Set<String> incomeMerchantIds) {
        java.util.Set<String> merchantIds = new java.util.LinkedHashSet<>(incomeMerchantIds);
        for (DeviceInfo d : deviceRepository.selectList(Wrappers.<DeviceInfo>lambdaQuery())) {
            String mid = d.getMerchantId();
            if (mid != null && !mid.isBlank()) {
                merchantIds.add(mid);
            }
        }
        long chargedTotal = 0L;
        for (String merchantId : merchantIds) {
            try {
                chargedTotal += chargeDailyPeriodFees(merchantId, yesterday);
            } catch (Exception e) {
                // 🔴 单商户失败不阻断全量（其余商户照常出账）。失败当日**不会自动补扣**
                //（老系统 L5：没扣到钱就不留费用行）——由运营台「按日期重跑日扣」手动补（F-4 拍板）。
                log.warn("daily fee charge failed for {}: {}", merchantId, e.getMessage());
            }
        }
        checkOverdueAndRemind(yesterday, merchantIds);
        return chargedTotal;
    }

    /**
     * 运营台「按日期重跑日扣」入口（2026-10-16 F-4 拍板 A=老系统 L10 人工带日期重跑）：
     * 漏扣不自动回补。幂等靠钱包流水 {@code refId=日期} 与台账 {@code uk_merchant_fee_date_type}，
     * 所以重跑只会补「那天没扣成」的商户，<b>不会重复扣已扣的日子，也不会追平当天改过的费率</b>
     *（老系统日报账单用的是「差额补记」，我们这里是「存在即跳过」——文案必须写清）。
     *
     * @return 本次实扣费用合计（分）
     */
    public long rerunDailyFees(LocalDate feeDate) {
        return chargeAndCheckAllMerchants(feeDate, java.util.Set.of());
    }


    /**
     * 欠费处置（2026-10-16 拍板：判据从「金额阈值」改「时间阈值 + 结清自动复机」）：
     * ① 存在未结清费用（PENDING 累计 &gt; 0）→ 当日发一条催缴信（bizId=日期 查重，每日最多一条）；
     * ② 未结清里<b>最老一行的账龄 ≥ lock_days（默认 7）</b> → 名下未锁柜机按 FEE_OVERDUE 前缀锁柜；
     * ③ 全部结清（PENDING 归零）→ 名下因费用欠缴被锁的柜机自动解锁（对标阿里云
     *    「欠费满 7 个自然日停服 / 补足欠款可正常恢复使用」，老系统<b>无</b>欠费锁柜先例）。
     * <p>费用为 0 或未配置的商户压根没有 PENDING 行 ⇒ debt=0 直接走 ③，永不会被锁。
     * 阈值可配：sys_config {@code merchant.overdue.lock_days}（默认 7；替代旧 {@code lock_months}）。
     */
    void checkOverdueAndRemind(LocalDate yesterday, java.util.Set<String> merchantIds) {
        String bizId = yesterday.format(ISO_FMT);
        int lockDays = lockDaysThreshold();
        LocalDate today = LocalDate.now(ScheduleZones.ZONE);
        for (String merchantId : merchantIds) {
            // 审计 T3（2026-10-15）：欠费判据=费用口径债（PENDING 台账），非钱包余额——
            // 余额负数还可能来自押金负扣/佣金冲正挂负（debitIfAbsentAllowNegative），
            // 那些不是费用欠缴，按余额判会把商户全部柜机误锁。
            List<MerchantFeeLedger> pendings = pendingFeeRows(merchantId);
            long debt = pendings.stream()
                    .mapToLong(r -> r.getAmountCents() == null ? 0L : r.getAmountCents()).sum();
            if (debt <= 0) {
                unlockFeeOverdue(merchantId);
                continue;
            }
            if (alreadySentType(merchantId, "FEE_REMINDER", bizId)) {
                // 当日已催缴
            } else {
                String merchantName = merchantRepository.findById(merchantId)
                        .map(Merchant::getMerchantName).orElse(merchantId);
                notificationService.notifyMerchant(merchantId, "merchant_fee_reminder",
                        Map.of("merchantName", merchantName,
                                "reminder", "您的平台费用已欠缴（未结清），请及时充值结清",
                                "debt", BigDecimal.valueOf(debt, 2).toPlainString()),
                        "FEE_REMINDER", bizId);
            }
            LocalDate oldest = pendings.stream()
                    .map(MerchantFeeLedger::getFeeDate)
                    .filter(java.util.Objects::nonNull)
                    .min(java.util.Comparator.naturalOrder())
                    .orElse(null);
            long overdueDays = oldest == null ? 0L
                    : java.time.temporal.ChronoUnit.DAYS.between(oldest, today);
            if (overdueDays >= lockDays) {
                lockFeeOverdue(merchantId, debt, overdueDays, lockDays);
            }
        }
    }

    /** 锁柜账龄阈值（天）；非法值退回 7（2026-10-16 拍板默认）。 */
    private int lockDaysThreshold() {
        try {
            return Math.max(1, Integer.parseInt(
                    configService.getValue("merchant.overdue.lock_days", "7").trim()));
        } catch (NumberFormatException | NullPointerException e) {
            return 7;
        }
    }

    private void lockFeeOverdue(String merchantId, long debt, long overdueDays, int lockDays) {
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
        for (DeviceInfo d : devices) {
            if (!Boolean.TRUE.equals(d.getSalesLocked())) {
                salesLockService.applySalesLock(null, d, true,
                        FEE_OVERDUE_LOCK_PREFIX + "：欠费 "
                                + BigDecimal.valueOf(debt, 2).toPlainString() + " 元，逾期 " + overdueDays
                                + " 天（满 " + lockDays + " 天锁柜，结清自动复机）",
                        true);
            }
        }
    }

    /**
     * 审计 T4（2026-10-15）：费用结清后的解锁联动——台账 SETTLED 后若未结清累计归零，
     * 立即解锁「费用欠缴锁」柜机（原先只能等次日 00:20 调度自愈，结清当日记「已结清却还锁着」）。
     * 由 MerchantFeeLedgerService.settle 在实际置 SETTLED 时调用。
     */
    public void unlockFeeOverdueIfCleared(String merchantId) {
        if (pendingFeeTotal(merchantId) <= 0) {
            unlockFeeOverdue(merchantId);
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
     * 周期费用日扣（2026-10-16 拍板 F-2=A / F-3=A）：
     * <ul>
     *   <li><b>流量费</b>：{@code device_info.data_fee_cents} 语义=<b>分/天</b>，逐台照收、<b>不除当月天数</b>、
     *       <b>不分租赁/买断</b>（老系统 L1/L2/L3：{@code MobileCardFeeGenerationTask} 逐台每日扣，
     *       全程不读 {@code machine_use_type}）。0 与 NULL 都是「这台柜不收 / 没配卡」的一等态，
     *       必须在进扣款路径前兜住（老系统对 NULL 拆箱 NPE 会整批任务崩）。</li>
     *   <li><b>租金</b>：仍为月额（分/月），只有 {@code coopMode=RENTAL} 生效，按当月天数摊销。</li>
     *   <li>顺序照老系统 L5「<b>先扣到钱才写行</b>」：扣款抛异常 ⇒ 当日不留行也不留欠费；
     *       扣款成功（或幂等命中已扣）⇒ 台账行直接 {@code SETTLED}（行即收据，不再挂永久 PENDING
     *       让同一笔费用「收一次、又按欠费锁一次柜」）。</li>
     * </ul>
     * 余额不足直减为负（欠费累计，老系统 L7 无下限同款）。返回<b>本次</b>实扣费用（分）——
     * 该日钱包流水已存在（幂等跳过，含同一次任务里循环两遍的商户）时返回 0，不重复计数。
     */
    long chargeDailyPeriodFees(String merchantId, LocalDate yesterday) {
        int daysInMonth = yesterday.lengthOfMonth();
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
        long rentDaily = 0L, dataDaily = 0L;
        for (DeviceInfo d : devices) {
            if ("RENTAL".equalsIgnoreCase(d.getCoopMode()) && d.getRentCents() != null) {
                rentDaily += d.getRentCents() / daysInMonth;
            }
            if (d.getDataFeeCents() != null && d.getDataFeeCents() > 0L) {
                dataDaily += d.getDataFeeCents();
            }
        }
        long dailyFee = rentDaily + dataDaily;
        if (dailyFee <= 0L) {
            // 名下柜机全部「零费/未配置」：不扣钱、不写行、不进欠费判据（用户 2026-10-16：
            // 「这些费用可以为 0——就是不收或者没有」；老系统 ServiceCharge.java:57-60 对 <=0 直接 return false）
            return 0L;
        }
        // 🔴 最终口径（2026-10-14 用户拍板对齐老系统）：余额**真扣**费用——「不从他们卡里扣，
        // 但余额肯定是减掉费用后的」，否则商户每天提现提走的是没扣费用的钱。余额可负=欠费。
        // 幂等扣款（refId=昨日日期，任务重跑不重扣；余额不足直减为负=欠费挂账）。
        // 扣款异常会直接抛出：调用方捕获后**不留行**（老系统 L5），当日漏扣不自动补，见 rerunDailyFees。
        boolean debited = walletService.debitIfAbsentAllowNegative(merchantId, dailyFee, "DAILY_FEE",
                FEE_REF_TYPE, yesterday.format(ISO_FMT),
                "周期费用日扣(租金月额/" + daysInMonth + "天 + 流量费分/天)");
        upsertFeeLedger(merchantId, yesterday, "REN", rentDaily, "日扣租金，扣款成功自动结清");
        upsertFeeLedger(merchantId, yesterday, "DATA", dataDaily, "日扣流量费，扣款成功自动结清");
        // F-5（2026-10-17）：false=该日钱包流水已存在（幂等跳过，本调用没再扣钱）。
        // 返回值只用于「本次实扣合计」（运营台重跑、任务汇总），跳过时必须报 0，
        // 否则同一天的费用会在合计里数两遍（收入商户循环 + 全量循环各算一次）。
        return debited ? dailyFee : 0L;
    }

    private void upsertFeeLedger(String merchantId, LocalDate feeDate, String feeType,
                                 long amountCents, String remark) {
        if (amountCents <= 0L) {
            // 0=不收 ⇒ 不留行。台账粒度是「商户×日×费用类型」，不像老系统的逐台明细，
            // 留 0 行只剩噪音（阿里云 0 元账单 / Cloudflare $0 invoice 也都是明细粒度才留）。
            return;
        }
        Long exists = feeLedgerRepository.selectCount(Wrappers.<MerchantFeeLedger>lambdaQuery()
                .eq(MerchantFeeLedger::getMerchantId, merchantId)
                .eq(MerchantFeeLedger::getFeeDate, feeDate)
                .eq(MerchantFeeLedger::getFeeType, feeType));
        if (exists != null && exists > 0) {
            return;
        }
        MerchantFeeLedger row = new MerchantFeeLedger();
        row.setMerchantId(merchantId);
        row.setFeeDate(feeDate);
        row.setFeeType(feeType);
        row.setAmountCents(amountCents);
        row.setStatus("SETTLED");
        row.setSettledAt(Instant.now());
        row.setRemark(remark);
        feeLedgerRepository.insert(row);
    }

    /** 该商户未结清费用行（催缴 + 锁柜账龄判据口径）。 */
    private List<MerchantFeeLedger> pendingFeeRows(String merchantId) {
        return feeLedgerRepository.selectList(Wrappers.<MerchantFeeLedger>lambdaQuery()
                .eq(MerchantFeeLedger::getMerchantId, merchantId)
                .eq(MerchantFeeLedger::getStatus, "PENDING"));
    }

    /** 该商户未结清费用累计（分）：结清联动 {@link #unlockFeeOverdueIfCleared} 的判据。 */
    long pendingFeeTotal(String merchantId) {
        return pendingFeeRows(merchantId).stream()
                .mapToLong(r -> r.getAmountCents() == null ? 0L : r.getAmountCents()).sum();
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
