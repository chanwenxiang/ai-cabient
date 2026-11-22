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
 * <p>渠道：站内信（merchant-mp 消息中心）+ 微信订阅消息（MA-377，商户端「每日经营日报」）。
 * 字段：商户名称 / 结算周期 / 销售额 / 成交笔数 / 客单价 / 费用 / 净入账，模板
 * {@code merchant_settlement}（V351，V499 改口径）。两个渠道**各自幂等**（站内信按商户、
 * 微信按「操作员 × 日期」）：任一侧当日成功，重跑只补发另一侧，互不阻塞。
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
    /** MA-377：早间微信日报是**独立任务**（各自 tryBegin、各自留执行记录）。 */
    static final String REPORT_PROCESS = "merchant-daily-report";
    private static final String TEMPLATE_CODE = "merchant_settlement";
    private static final String BIZ_TYPE = "DAILY_INCOME";
    static final String FEE_OVERDUE_LOCK_PREFIX = "费用欠缴锁柜";
    /** 钱包欠款锁柜的原因前缀（CB-086）。与费用欠费锁**分开拥有**：两边的复机扫描互不代 unlock。 */
    static final String WALLET_ARREARS_LOCK_PREFIX = "钱包欠款锁柜";
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
    /** CB-086：钱包欠款账龄探针（只读，结果拼进日结执行记录 + 一条聚合群告警）。 */
    private final MerchantWalletArrearsService walletArrearsService;
    /** MA-377：微信日报投递（按商户反查操作员；站内信之外的独立渠道）。 */
    private final MerchantDailyReportNotifier dailyReportNotifier;

    public MerchantDailyIncomeNotifyScheduler(MerchantMapper merchantRepository,
                                              OrderRevenueSplitMapper splitRepository,
                                              NotificationService notificationService,
                                              NotificationLogMapper notificationLogRepository,
                                              ScheduledTaskService taskService,
                                              DeviceInfoMapper deviceRepository,
                                              MerchantFeeLedgerMapper feeLedgerRepository,
                                              MerchantWalletService walletService,
                                              SystemConfigService configService,
                                              DeviceSalesLockService salesLockService,
                                              MerchantWalletArrearsService walletArrearsService,
                                              MerchantDailyReportNotifier dailyReportNotifier) {
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
        this.walletArrearsService = walletArrearsService;
        this.dailyReportNotifier = dailyReportNotifier;
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
            LocalDate yesterday = yesterdayOf();
            NotifyOutcome outcome = sendDailyIncomeNotify(yesterday);
            summary = "昨日(" + yesterday + ")日结通知发送 " + outcome.sent() + " 商户"
                    // F-5（2026-10-17）：扣费失败不再只在日志里静默——写进运营台执行记录，
                    // 否则商户收到的通知里 fee=0.00 没人知道那是「没扣成」而不是「免费」
                    + (outcome.feeChargeFailed() > 0
                        ? "；另有 " + outcome.feeChargeFailed() + " 商户日扣失败（当日未扣到费，"
                            + "通知里费用按实扣显示为 0，需运营台「按日期重跑日扣」补）"
                        : "");
            // CB-086：钱包欠款账龄只读探针——结果进运营台「执行记录」，不改余额/提现/柜机状态。
            summary += walletArrearsSummary(LocalDate.now(ScheduleZones.ZONE));
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
     * 「昨日」的唯一口径（两个任务共用）：00:20 与 07:30 都按 Asia/Shanghai 的日历日回退一天。
     * 抽成一处是刻意的——两个任务各算一遍「今天减一天」，就会有「跨零点那两秒
     * 两个任务算出不同日期」的窗口，也躲开 check-window-limit-honesty 的重复登记。
     */
    private static LocalDate yesterdayOf() {
        return LocalDate.now(ScheduleZones.ZONE).minusDays(1);
    }

    /**
     * 微信经营日报（MA-377）：早上 07:30 单独推送「昨日销售额 / 笔数 / 客单价」。
     *
     * <p><b>为什么不跟 00:20 的日结一起发</b>：00:20 的站内信是**静默**入库（商户不点开就
     * 不知道），微信订阅消息却会真弹一条通知——半夜弹窗只会换来商户关掉提醒。竞品
     * （友宝/丰e足食一类）也都是早间推前一日经营报告。故：凌晨只做「扣费 + 站内信」，
     * 早上单独推微信；两个任务各自 tryBegin、各自留执行记录。
     */
    @Scheduled(
            cron = "${aicabinet.merchant-daily-report.scheduled-cron:0 30 7 * * *}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void notifyYesterdayReport() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(REPORT_PROCESS, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "经营日报未执行";
        try {
            LocalDate yesterday = yesterdayOf();
            int sent = sendWechatReportsOnly(yesterday);
            summary = "昨日(" + yesterday + ")经营日报微信送达 " + sent + " 人";
            log.info("scheduled merchant daily report completed: {}", summary);
        } catch (Exception e) {
            failed = true;
            taskService.finish(REPORT_PROCESS, "FAILED", e.getMessage(), start);
            log.error("scheduled merchant daily report failed", e);
        } finally {
            if (!failed) {
                taskService.finish(REPORT_PROCESS, "SUCCESS", summary, start);
            }
        }
    }

    /**
     * 只发微信日报：不扣费、不写站内信（扣费与站内信属 00:20 的日结任务，两件事不能混）。
     * 幂等由 {@link MerchantDailyReportNotifier} 按「操作员 × 日期」保证，重跑只补发未收到的。
     *
     * @return 送达人数
     */
    int sendWechatReportsOnly(LocalDate yesterday) {
        Instant from = yesterday.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant to = yesterday.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        List<OrderRevenueSplit> splits = splitRepository.selectList(Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, from)
                .lt(OrderRevenueSplit::getCreatedAt, to));
        Map<String, DailySales> salesByMerchant = mergeDeviceMerchants(aggregateSales(splits));
        if (salesByMerchant.isEmpty()) {
            return 0;
        }
        String bizId = yesterday.format(ISO_FMT);
        String period = yesterday.format(PERIOD_FMT);
        int sent = 0;
        for (Map.Entry<String, DailySales> e : salesByMerchant.entrySet()) {
            sent += dailyReportNotifier.send(new MerchantDailyReportNotifier.MerchantDailyReport(
                    e.getKey(), bizId, period, e.getValue().salesCents(), e.getValue().orderCount()));
        }
        return sent;
    }

    /**
     * 日结通知结果：{@code sent}=站内信实际发送商户数，{@code feeChargeFailed}=昨日日扣失败
     * 商户数（F-5：失败数必须冒泡到运营台执行记录，不许只在日志里静默）。
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
        Map<String, DailySales> salesByMerchant = mergeDeviceMerchants(aggregateSales(splits));
        if (salesByMerchant.isEmpty()) {
            // 审计 T4（2026-10-15）：early-return 只对「通知发送」生效——费用日扣与欠费检查
            // （chargeAndCheckAllMerchants，CB-041）必须照跑，否则全员零销售的日子整批
            // 费用不出账、欠费不催缴、误锁不解锁（固定租金是合同义务，与销售脱钩）。
            chargeAndCheckAllMerchants(yesterday, java.util.Set.of());
            return new NotifyOutcome(0, 0);
        }
        String bizId = yesterday.format(ISO_FMT);
        String period = yesterday.format(PERIOD_FMT);
        // 先把「昨日经营数据」物化成日报对象：站内信与微信两条通道共用同一份数字，
        // 避免两条链路各自取数导致「站内信 1901.23、微信 1901.2」这种对不上账的情况。
        List<MerchantDailyReportNotifier.MerchantDailyReport> reports = new java.util.ArrayList<>();
        for (Map.Entry<String, DailySales> e : salesByMerchant.entrySet()) {
            reports.add(new MerchantDailyReportNotifier.MerchantDailyReport(
                    e.getKey(), bizId, period, e.getValue().salesCents(), e.getValue().orderCount()));
        }
        int sent = 0;
        int feeChargeFailed = 0;
        for (MerchantDailyReportNotifier.MerchantDailyReport report : reports) {
            String merchantId = report.merchantId();
            if (alreadySentInApp(merchantId, bizId)) {
                // 站内信已发：不再扣费（扣费幂等靠这条判断，重复扣费比重复通知严重得多）、
                // 也不再写第二条站内信。微信侧走下面的独立循环补发。
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
            long incomeCents = report.salesCents();
            long netCents = incomeCents - feeCents;
            notificationService.notifyMerchant(merchantId, TEMPLATE_CODE,
                    Map.of("merchantName", merchantName,
                            "period", period,
                            "amount", BigDecimal.valueOf(incomeCents, 2).toPlainString(),
                            // MA-377：商户要的是「今天卖了多少」，只有金额看不出单量大小
                            "orders", String.valueOf(report.orderCount()),
                            "avg", BigDecimal.valueOf(report.avgCents(), 2).toPlainString(),
                            "fee", BigDecimal.valueOf(feeCents, 2).toPlainString(),
                            "net", BigDecimal.valueOf(netCents, 2).toPlainString()),
                    BIZ_TYPE, bizId);
            sent++;
        }
        // MA-377：微信日报**不在这里发**——00:20 弹微信通知会吵到商户（站内信是静默入库，
        // 微信是真弹窗）。微信侧由 07:30 的 notifyYesterdayReport 单独推，两件事不混。
        // CB-041（2026-10-14 拍板：费用摊销与销售脱钩——固定租金是合同义务，零销售照摊；
        // 免费投放本来费用为 0 不受影响）：日扣与欠费检查覆盖**全部有柜商户**，
        // 不再只扫昨日有收入商户（否则零销售商户费用永远不出账、欠费永远不催缴）。
        chargeAndCheckAllMerchants(yesterday, salesByMerchant.keySet());
        return new NotifyOutcome(sent, feeChargeFailed);
    }

    /** 设备生命周期取值（V127__device_asset_lifecycle.sql:15）。 */
    private static final String LIFECYCLE_INBOUND = "INBOUND";
    private static final String LIFECYCLE_RETIRED = "RETIRED";

    /**
     * 名下有柜机的商户集合（去重、保序）——<b>费用口径</b>，全表枚举，不做生命周期过滤：
     * 柜机是费用的载体（欠费源于柜机费用），退役柜/入库柜也可能有未结费用，故
     * {@link #chargeAndCheckAllMerchants} 必须用这个全集。
     */
    private java.util.Set<String> merchantIdsWithDevices() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (DeviceInfo d : deviceRepository.selectList(Wrappers.<DeviceInfo>lambdaQuery())) {
            String mid = d.getMerchantId();
            if (mid != null && !mid.isBlank()) {
                ids.add(mid);
            }
        }
        return ids;
    }

    /**
     * 「在运营柜」的商户集合——<b>日报口径</b>（P2③，2026-11-21 用户复核）：排除
     * INBOUND（入库未投放）与 RETIRED（已退役）。只剩这两类柜的商户每天收 0 元日报纯噪音，
     * 与「名下有柜机＝在运营」的直觉不符。IDLE/DEPLOYED/RETURNING 都算在运营
     * （RETURNING 尚未入库、IDLE 是空闲在场，仍属商户资产在册）。
     *
     * <p>🔴 与费用口径分开：费用催缴按资产归属（全集），日报按在运营，两者不共用集合——
     * 排除退役柜不得连带停掉它的欠费催缴。</p>
     */
    private java.util.Set<String> merchantIdsWithOperatingDevices() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (DeviceInfo d : deviceRepository.selectList(Wrappers.<DeviceInfo>lambdaQuery())) {
            String ls = d.getLifecycleStatus();
            if (LIFECYCLE_INBOUND.equals(ls) || LIFECYCLE_RETIRED.equals(ls)) {
                continue;
            }
            String mid = d.getMerchantId();
            if (mid != null && !mid.isBlank()) {
                ids.add(mid);
            }
        }
        return ids;
    }

    /**
     * 把「昨日有销售」的聚合结果扩成「日报该覆盖的商户集合」：有柜机但昨日零成交的商户补一条
     * 0 销售额 / 0 笔的日报。
     *
     * <p><b>为什么改判</b>：2026-10-14 拍板「0.00 不发」（见 {@link #aggregateSales}），出发点是
     * 别拿 0 元打扰人；但那一版的直接后果是——商户没成交的日子什么都收不到，于是分不清
     * 「今天确实没生意」和「通知坏了/没生效」。2026-11-20 用户改判：<b>有柜机的商户每天都要收到</b>，
     * 0 也要收到，这也是竞品早间经营报告的通行做法。范围限「名下有<b>在运营</b>柜机」
     * （排除 INBOUND/RETIRED，见 {@link #merchantIdsWithOperatingDevices}），
     * 不给无柜/仅退役柜商户发 0 元；费用催缴仍按全表口径，不受影响。
     *
     * <p>{@link #aggregateSales} 本身口径不变（仍只保留合计 &gt;0 的商户），它是「有成交」的视图，
     * 扩集合只发生在调度器这一层，免得影响其它调用点与既有单测。
     */
    private Map<String, DailySales> mergeDeviceMerchants(Map<String, DailySales> salesByMerchant) {
        Map<String, DailySales> merged = new java.util.LinkedHashMap<>(salesByMerchant);
        for (String merchantId : merchantIdsWithOperatingDevices()) {
            merged.putIfAbsent(merchantId, new DailySales(0L, 0));
        }
        return merged;
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
        merchantIds.addAll(merchantIdsWithDevices());
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
        // CB-086：钱包欠款连续满账龄 ⇒ 停售；结清自动复机（与费用欠费锁分开拥有设备锁）。
        // 账龄按「今天」判：本方法也被「按日期重跑日扣」复用，用 feeDate+1 会让停售判据跟着回拨
        enforceWalletArrears(LocalDate.now(ScheduleZones.ZONE));
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

    /**
     * CB-086：钱包欠款账龄摘要（只读探针）。探针自身故障绝不连坐日结主流程——
     * 与「费用日扣单商户失败不阻断全量」同口径。
     */
    private String walletArrearsSummary(LocalDate today) {
        if (walletArrearsService == null) {
            return "";
        }
        try {
            String summary = walletArrearsService.dailySummary(today);
            return summary == null ? "" : summary;
        } catch (Exception e) {
            log.warn("merchant wallet arrears probe failed: {}", e.getMessage());
            return "；欠款账龄探针失败（见日志）";
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
        // CB-086：费用已清但钱包仍在欠款账龄内 ⇒ 保持锁定不改归属。钱包扫描只锁「未锁」设备，
        // 不会重复上锁；等钱包结清后下一次费用扫描会重新走到这里完成解锁。
        if (walletArrearsService != null && walletArrearsService.inArrears(merchantId)) {
            log.info("fee overdue cleared but wallet arrears remain, keep locked merchant={}", merchantId);
            return;
        }
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
     * CB-086（2026-11-13 用户拍板）：钱包欠款连续满 {@code merchant.wallet_arrears.lock_after_days}
     * （默认 90，0=关闭）⇒ 名下**未锁**柜机停售；余额回到非负后自动复机。
     *
     * <p>安全判据（每条都有反例测试）：
     * <ol>
     *   <li>只锁当前未锁的设备——费用欠费锁/离线停售锁的归属不被改写；</li>
     *   <li>复机只看原因以本前缀开头的设备，且要求「不再欠款」**且**「无未结费用」；</li>
     *   <li>账龄不可判（无流水）不锁；锁柜阈值 0=关闭（告警可独立保留）；</li>
     *   <li>单商户异常不阻断其余商户（与费用日扣同口径）。</li>
     * </ol>
     */
    void enforceWalletArrears(LocalDate today) {
        MerchantWalletArrearsService svc = walletArrearsService;
        if (svc == null) {
            return;
        }
        int lockDays = svc.lockThresholdDays();
        if (lockDays <= 0) {
            // 策略关闭：把本前缀的锁全部释放，否则关闸会把已锁商户永久钉在停售上。
            // 复机仍守「有未结费用不解除」——费用锁的归属不受影响。
            unlockWalletArrearsDevices(mid -> true, "钱包欠款锁柜已关闭（策略关闭）");
            return;
        }
        for (MerchantWalletArrearsService.Arrears hit : svc.overThreshold(lockDays, today)) {
            try {
                lockWalletArrears(hit, lockDays);
            } catch (Exception e) {
                log.warn("wallet arrears lock failed merchant={}: {}", hit.merchantId(), e.getMessage());
            }
        }
        unlockClearedWalletArrears(svc);
    }

    private void lockWalletArrears(MerchantWalletArrearsService.Arrears hit, int lockDays) {
        List<DeviceInfo> devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, hit.merchantId()));
        String reason = WALLET_ARREARS_LOCK_PREFIX + "：钱包欠款 "
                + BigDecimal.valueOf(hit.arrearsCents(), 2).toPlainString()
                + " 元，连续 " + hit.ageDays() + " 天（满 " + lockDays + " 天停售，结清自动复机）";
        for (DeviceInfo d : devices) {
            if (!Boolean.TRUE.equals(d.getSalesLocked())) {
                salesLockService.applySalesLock(null, d, true, reason, true);
            }
        }
    }

    private void unlockClearedWalletArrears(MerchantWalletArrearsService svc) {
        unlockWalletArrearsDevices(mid -> !svc.inArrears(mid), "钱包欠款已结清");
    }

    /**
     * 解除「钱包欠款锁柜」前缀的设备锁。
     *
     * @param cleared 该商户是否已不欠款（策略关闭时传恒 true）
     * @param reason  复机原因（写进设备锁售审计）
     */
    private void unlockWalletArrearsDevices(java.util.function.Predicate<String> cleared, String reason) {
        List<DeviceInfo> locked = deviceRepository.selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getSalesLocked, true)
                .likeRight(DeviceInfo::getSalesLockReason, WALLET_ARREARS_LOCK_PREFIX));
        if (locked.isEmpty()) {
            return;
        }
        java.util.Map<String, List<DeviceInfo>> byMerchant = new java.util.LinkedHashMap<>();
        for (DeviceInfo d : locked) {
            if (d.getMerchantId() != null && !d.getMerchantId().isBlank()) {
                byMerchant.computeIfAbsent(d.getMerchantId(), k -> new java.util.ArrayList<>()).add(d);
            }
        }
        for (Map.Entry<String, List<DeviceInfo>> e : byMerchant.entrySet()) {
            // 结清 = 不再欠款且无未结费用；只解本前缀锁住的设备，费用锁/人工锁不动
            if (!cleared.test(e.getKey()) || pendingFeeTotal(e.getKey()) > 0) {
                continue;
            }
            for (DeviceInfo d : e.getValue()) {
                try {
                    salesLockService.applySalesLock(null, d, false, reason, true);
                } catch (Exception ex) {
                    log.warn("wallet arrears unlock failed device={}: {}", d.getDeviceId(), ex.getMessage());
                }
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
                // MA-286：逐日 floor 会把 0~29 分/台/月的余数永久吞掉（系统性少收）。
                // 余数按日序分摊：每天 base +（当月第 n 天 ≤ 余数 ? 1 分 : 0），月合计恰等于月额，
                // 任何一天的偏差至多 1 分且方向只会多收一天的分（不会吞钱）。
                long rent = d.getRentCents();
                int base = (int) (rent / daysInMonth);
                int rem = (int) (rent % daysInMonth);
                rentDaily += base + (yesterday.getDayOfMonth() <= rem ? 1 : 0);
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
        upsertFeeLedger(merchantId, yesterday, MerchantFeeLedger.FEE_TYPE_REN, rentDaily,
                "日扣租金，扣款成功自动结清");
        // B4（V486）：流量费源头本就逐台计收 ⇒ 台账恢复 device 维度，逐台落行。
        // RENT 仍为商户级聚合（站点合同维度，已有 site_rent_bill 链路）。
        for (DeviceInfo d : devices) {
            if (d.getDataFeeCents() == null || d.getDataFeeCents() <= 0L) {
                continue; // 0/NULL = 这台柜不收/没配卡，不留行（与聚合口径一致）
            }
            upsertFeeLedger(merchantId, yesterday, MerchantFeeLedger.FEE_TYPE_DATA,
                    d.getDataFeeCents(), "日扣流量费，扣款成功自动结清", d.getDeviceId());
        }
        // F-5（2026-10-17）：false=该日钱包流水已存在（幂等跳过，本调用没再扣钱）。
        // 返回值只用于「本次实扣合计」（运营台重跑、任务汇总），跳过时必须报 0，
        // 否则同一天的费用会在合计里数两遍（收入商户循环 + 全量循环各算一次）。
        return debited ? dailyFee : 0L;
    }

    private void upsertFeeLedger(String merchantId, LocalDate feeDate, String feeType,
                                 long amountCents, String remark) {
        upsertFeeLedger(merchantId, feeDate, feeType, amountCents, remark, null);
    }

    /**
     * B4：deviceId=null 为商户级聚合行（RENT/历史调用方）；DATA 逐台传 deviceId。
     * 幂等键升级「商户×日×类型×设备」，但**兼容旧聚合行**：同 (商户,日,类型) 存在任意行
     * （含 device_id IS NULL 的历史聚合行）即整体跳过——防止切换日「旧行+新逐台行」双收据。
     */
    private void upsertFeeLedger(String merchantId, LocalDate feeDate, String feeType,
                                 long amountCents, String remark, String deviceId) {
        if (amountCents <= 0L) {
            // 0=不收 ⇒ 不留行。台账粒度是「商户×日×费用类型(×设备)」，不像老系统的逐台明细，
            // 留 0 行只剩噪音（阿里云 0 元账单 / Cloudflare $0 invoice 也都是明细粒度才留）。
            return;
        }
        // 幂等查键：有 deviceId 时查 (商户,日,类型,设备)；
        // 无 deviceId（商户级）或逐台首写防御时查 (商户,日,类型) 任意行（含旧聚合行）
        Long exists;
        if (deviceId != null) {
            Long sameDevice = feeLedgerRepository.selectCount(Wrappers.<MerchantFeeLedger>lambdaQuery()
                    .eq(MerchantFeeLedger::getMerchantId, merchantId)
                    .eq(MerchantFeeLedger::getFeeDate, feeDate)
                    .eq(MerchantFeeLedger::getFeeType, feeType)
                    .eq(MerchantFeeLedger::getDeviceId, deviceId));
            if (sameDevice != null && sameDevice > 0) {
                return;
            }
            // 该商户当日该类型已有聚合行（device_id IS NULL）⇒ 切换日兼容：整体跳过
            exists = feeLedgerRepository.selectCount(Wrappers.<MerchantFeeLedger>lambdaQuery()
                    .eq(MerchantFeeLedger::getMerchantId, merchantId)
                    .eq(MerchantFeeLedger::getFeeDate, feeDate)
                    .eq(MerchantFeeLedger::getFeeType, feeType)
                    .isNull(MerchantFeeLedger::getDeviceId));
        } else {
            exists = feeLedgerRepository.selectCount(Wrappers.<MerchantFeeLedger>lambdaQuery()
                    .eq(MerchantFeeLedger::getMerchantId, merchantId)
                    .eq(MerchantFeeLedger::getFeeDate, feeDate)
                    .eq(MerchantFeeLedger::getFeeType, feeType));
        }
        if (exists != null && exists > 0) {
            return;
        }
        MerchantFeeLedger row = new MerchantFeeLedger();
        row.setMerchantId(merchantId);
        row.setDeviceId(deviceId);
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
    /** 当日单个商户的经营数据：销售额（分）+ 成交笔数。 */
    record DailySales(long salesCents, int orderCount) {
    }

    /**
     * 按商户聚合「昨日销售额 + 成交笔数」（冲正单不计、空商户不计、负数按 0）。
     *
     * <p>笔数口径 = 参与聚合的非冲正分账行数（{@code order_revenue_split} 一单一行），
     * 含 0 元行：那是一笔真实成交、只是分给商户的部分为 0，漏掉它会让客单价虚高。
     * 只有合计 &gt;0 的商户才进结果集（0.00 不发，2026-10-14 拍板）。
     *
     * <p>⚠️ 「0.00 不发」只决定<b>本方法</b>保留谁；调度器另用
     * {@code mergeDeviceMerchants} 给「名下有柜机但昨日零成交」的商户补 0 元日报
     * （2026-11-20 改判：商户每天都要收到，0 也要收到，否则分不清「没生意」和「通知坏了」）。
     * 改这一行口径前先看那个方法的注释，别把两层判据合成一层。
     */
    static Map<String, DailySales> aggregateSales(List<OrderRevenueSplit> splits) {
        Map<String, long[]> acc = new java.util.LinkedHashMap<>();
        for (OrderRevenueSplit s : splits) {
            String merchantId = s.getMerchantId();
            if (merchantId == null || merchantId.isBlank()
                    || MerchantSettlementBillService.isReversed(s.getStatus())) {
                continue;
            }
            long[] v = acc.computeIfAbsent(merchantId, k -> new long[2]);
            v[0] += Math.max(0L, s.getMerchantCents());
            v[1] += 1;
        }
        Map<String, DailySales> byMerchant = new java.util.LinkedHashMap<>();
        acc.forEach((merchantId, v) -> {
            if (v[0] > 0) {
                byMerchant.put(merchantId, new DailySales(v[0], (int) v[1]));
            }
        });
        return byMerchant;
    }

    /** 仅金额视图（既有单测与调用点保留）；口径与 {@link #aggregateSales} 完全一致。 */
    static Map<String, Long> aggregateIncome(List<OrderRevenueSplit> splits) {
        Map<String, Long> byMerchant = new java.util.LinkedHashMap<>();
        aggregateSales(splits).forEach((merchantId, sales) -> byMerchant.put(merchantId, sales.salesCents()));
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

    /**
     * 站内信是否已发（**只认 IN_APP 渠道**）。
     *
     * <p>MA-377：加 channel 条件前，微信日报（同 template_code / audience / merchantId / bizId）
     * 的日志行会让本判断误命中，导致「只补发了微信」的商户被当成已发站内信而漏发。
     * 两个渠道各有各的幂等，日志必须按渠道区分。
     */
    private boolean alreadySentInApp(String merchantId, String bizId) {
        Long c = notificationLogRepository.selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getTemplateCode, TEMPLATE_CODE)
                .eq(NotificationLog::getChannel, "IN_APP")
                .eq(NotificationLog::getAudience, "MERCHANT")
                .eq(NotificationLog::getMerchantId, merchantId)
                .eq(NotificationLog::getBizType, BIZ_TYPE)
                .eq(NotificationLog::getBizId, bizId));
        return c != null && c > 0;
    }
}
