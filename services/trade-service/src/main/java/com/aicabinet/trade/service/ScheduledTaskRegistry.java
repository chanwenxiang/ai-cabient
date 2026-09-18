package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 定时任务注册表：任务元数据与手动触发执行入口。
 * <p>action 指向各定时任务自身（含执行守卫），保证手动触发与自动调度走同一套
 * 启停开关 + 分布式锁 + 执行记录。</p>
 * <p><b>这里是「被看护」的边界，不是「被 XXL 托管」的边界。</b>业务定时任务已全量托管
 * （见 {@link XxlJobManagedTasks}），但仍有 2 个刻意留在 Spring 常驻：超期看护
 * {@code scheduled-task-stale-monitor}（托管后会与调度中心一起停跑）与 {@code cache-purge}
 * （本机缓存清理，每实例自清、通过 {@code LOCK_ONLY_TASKS} 豁免而不进本注册表）。</p>
 */
@Component
public class ScheduledTaskRegistry {
    private static final String MARKETING = "MARKETING";
    private static final String V_15 = "每 15 分钟";
    private static final String FINANCE = "FINANCE";
    private static final String SYSTEM = "SYSTEM";
    private static final String V_30 = "每 30 秒";
    private static final String V_60 = "每 60 秒";
    private static final String V_5 = "每 5 分钟";
    private static final String TRADE = "TRADE";


    /**
     * xxlManaged=true：开启 XXL-JOB 后内置 {@code @Scheduled} 经 tryBegin 让位，
     * 由调度中心统一触发（见 {@link XxlJobManagedTasks}）。
     */
    public record TaskDescriptor(String key, String name, String group, String scheduleDesc,
                                 long leaseSeconds, boolean xxlManaged, Runnable action) {
    }

    private final Map<String, TaskDescriptor> tasks = new LinkedHashMap<>();

    /**
     * 「因为条件装配而有意不注册」的任务 key（当前仅 {@code ops-fee-bill-monthly}）。
     * <p>与「漏注册」区分开：看护对这份名单豁免超期告警，名单之外却查不到 descriptor 的，
     * 一律按缺陷报出（{@code NOT_REGISTERED}）。</p>
     */
    private final Set<String> conditionallyAbsent = new LinkedHashSet<>();

    public ScheduledTaskRegistry(UnpaidOrderScheduler unpaidOrderScheduler,
                                 RechargeOrderScheduler rechargeOrderScheduler,
                                 ProfitSharingRetryScheduler profitSharingRetryScheduler,
                                 ReconciliationScheduler reconciliationScheduler,
                                 LineCommissionJob lineCommissionJob,
                                 FinanceMarginLockScheduler financeMarginLockScheduler,
                                 CouponService couponService,
                                 SlaMetricsService slaMetricsService,
                                 DeviceAvailabilityJobScheduler deviceAvailabilityJobScheduler,
                                 ExpiryAlertScheduler expiryAlertScheduler,
                                 MerchantWorkbenchNotifyScheduler merchantWorkbenchNotifyScheduler,
                                 DisputeSlaScheduler disputeSlaScheduler,
                                 ReplenishmentTimeoutScheduler replenishmentTimeoutScheduler,
                                 DataConsistencyService dataConsistencyService,
                                 DevicePresenceService devicePresenceService,
                                 OpsExceptionScannerService opsExceptionScannerService,
                                 SessionExpireService sessionExpireService,
                                 CompensationTaskScheduler compensationTaskScheduler,
                                 PointsExpiryScheduler pointsExpiryScheduler,
                                 CouponExpiryReminderScheduler couponExpiryReminderScheduler,
                                 GrowthLogArchiveScheduler growthLogArchiveScheduler,
                                 SkuReviewScheduler skuReviewScheduler,
                                 RiskAutoDispositionService riskAutoDispositionService,
                                 DeviceTempPlanService deviceTempPlanService,
                                 ObjectProvider<OpsFeeBillJob> opsFeeBillJobProvider,
                                 ScheduledTaskStaleMonitor scheduledTaskStaleMonitor) {
        register("device-presence", "设备离线巡检", "DEVICE", V_60, 600,
                devicePresenceService::markStaleDevicesOffline);
        register("session-opening-expire", "开门超时会话清理", TRADE, V_30, 600,
                sessionExpireService::expireStaleOpeningSessions);
        register("session-restock-expire", "补货会话超时清理", TRADE, V_60, 600,
                sessionExpireService::expireStaleRestockShoppingSessions);
        register("session-door-open-expire", "消费者开门超时清理", TRADE, V_60, 600,
                sessionExpireService::expireStaleConsumerShoppingSessions);
        register("session-recognizing-expire", "识别结算超时升级", TRADE, V_60, 600,
                sessionExpireService::expireStaleRecognizingSessions);
        register("ops-exception-scanner", "异常卡点扫描", "OPS", V_30, 600,
                opsExceptionScannerService::scan);
        register("compensation-process", "补偿任务处理", SYSTEM, V_30, 600,
                compensationTaskScheduler::processCompensationTasks);
        register("compensation-retry", "补偿任务重试", SYSTEM, V_60, 600,
                compensationTaskScheduler::retryFailedTransactions);
        register("replenishment-timeout", "补货超时收口", "WAREHOUSE", V_60, 600,
                replenishmentTimeoutScheduler::expireStaleCheckedInTasks);
        // 必须走 performConsistencyCheck（含 tryBegin/finish），不能直接 runConsistencyCheck，否则手动触发不写耗时/结果说明
        register("data-consistency", "数据一致性巡检", "OPS", V_5, 900,
                dataConsistencyService::performConsistencyCheck);
        register("unpaid-cancel", "未付订单自动取消", TRADE, V_15, 600,
                unpaidOrderScheduler::autoCancelExpired);
        register("recharge-cancel", "充值单自动取消", TRADE, V_5, 600,
                rechargeOrderScheduler::autoCancelExpired);
        register("device-auto-unlock", "稳定在线自动解锁", "DEVICE", V_5, 600,
                deviceAvailabilityJobScheduler::autoUnlockFallback);
        register("merchant-notify", "商户工作台通知", "MERCHANT", V_15, 600,
                merchantWorkbenchNotifyScheduler::pushWorkbenchAlerts);
        register("dispute-sla", "争议 SLA 巡检", "OPS", V_15, 600,
                disputeSlaScheduler::checkDisputeSla);
        register("profit-sharing-retry", "分账重试", FINANCE, V_15, 600,
                profitSharingRetryScheduler::retryFailedSplits);
        register("expiry-alert", "库存临期预警", "WAREHOUSE", "每 60 分钟", 600,
                expiryAlertScheduler::scanExpiry);
        register("reconciliation", "每日对账", FINANCE, ScheduleZones.desc("每日 01:30"), 1800,
                reconciliationScheduler::runDailyReconciliation);
        // H38 提现打款超时兜底：原先只有 @Scheduled(fixedDelay) 且**未登记** —— 执行记录被
        // finish() 静默丢弃，运营台看不见、不能启停、不能手动触发（门禁 Rule 4 已拦下）。
        register("withdraw-paying-timeout", "提现打款超时兜底", FINANCE, "每 10 分钟", 600,
                reconciliationScheduler::failStalePayingWithdraws);
        register("line-commission", "线长佣金入账", FINANCE, ScheduleZones.desc("每日 00:20"), 1800,
                lineCommissionJob::postDailyCommission);
        register("finance-margin", "财务保证金固化", FINANCE, ScheduleZones.desc("每日 00:05"), 1800,
                financeMarginLockScheduler::solidifyYesterday);
        // 条件注册：OpsFeeBillJob 带 @ConditionalOnProperty(auto-generate-enabled, matchIfMissing=true)，
        // 关闭时不建 bean；直接构造注入会让整个服务起不来，故用 ObjectProvider 探测。
        // 未注册时运营台「立即执行」与 XXL 触发都会明确报「任务未注册」，而不是静默不跑。
        //
        // 「有意关闭」与「漏注册」必须分开记账：前者要豁免看护超期告警（否则关一个开关换来
        // XXL 每月 handleFail + 看护 32 天后误报 OVERDUE 两处噪音）；后者是真缺陷，必须照报。
        // 故这里显式登记到 {@link #conditionallyAbsent}，看护只豁免这一份名单。
        OpsFeeBillJob feeBillJob = opsFeeBillJobProvider.getIfAvailable();
        if (feeBillJob != null) {
            register("ops-fee-bill-monthly", "周期费用月结出账", FINANCE,
                    ScheduleZones.desc("每月 1 日 01:30"), 1800, feeBillJob::generateMonthlyFees);
        } else {
            conditionallyAbsent.add("ops-fee-bill-monthly");
        }
        register("coupon-expire", "优惠券过期处理", MARKETING, ScheduleZones.desc("每日 02:00"), 600,
                couponService::expireOverdueCoupons);
        register("points-expiry", "积分过期管理", MARKETING, ScheduleZones.desc("每 6 小时"), 600,
                pointsExpiryScheduler::scan);
        register("coupon-expiry-remind", "优惠券临期提醒", MARKETING, ScheduleZones.desc("每 6 小时"), 600,
                couponExpiryReminderScheduler::scan);
        register("growth-log-archive", "增长日志归档", SYSTEM, ScheduleZones.desc("每日 03:00"), 600,
                growthLogArchiveScheduler::archive);
        register("sku-review-daily", "选品诊断每日刷新", MARKETING, ScheduleZones.desc("每日 04:00"), 600,
                skuReviewScheduler::scan);
        register("sla-snapshot", "SLA 日快照", "OPS", ScheduleZones.desc("每日 00:05"), 600,
                slaMetricsService::snapshotDaily);
        register("kpi-snapshot", "设备可用性 KPI 快照", "OPS", ScheduleZones.desc("每日 01:10"), 600,
                deviceAvailabilityJobScheduler::kpiSnapshotFallback);
        register("risk-auto-disposition", "风控事件自动处置", "OPS", "每 15 分钟", 600,
                riskAutoDispositionService::runScheduled);
        register("temp-plan", "温控计划下发", "DEVICE", V_60, 600,
                deviceTempPlanService::scheduledApply);
        // 托管任务停跑的唯一兜底：刻意不列入 XxlJobManagedTasks（否则会跟着一起让位，等于没看护）
        register("scheduled-task-stale-monitor", "定时任务超期看护", "SYSTEM", V_5, 600,
                scheduledTaskStaleMonitor::check);
    }

    public Optional<TaskDescriptor> get(String key) {
        return Optional.ofNullable(tasks.get(key));
    }

    /**
     * 该 key 是否因条件装配（如 {@code aicabinet.fee-bill.auto-generate-enabled=false}）而有意未注册。
     * <p>看护据此豁免：「关掉一个开关」不应换来持续的超期告警。</p>
     */
    public boolean isConditionallyAbsent(String key) {
        return conditionallyAbsent.contains(key);
    }

    public Collection<TaskDescriptor> all() {
        return tasks.values();
    }

    private void register(String key, String name, String group, String scheduleDesc,
                          long leaseSeconds, Runnable action) {
        tasks.put(key, new TaskDescriptor(
                key, name, group, scheduleDesc, leaseSeconds, XxlJobManagedTasks.isManaged(key), action));
    }
}
