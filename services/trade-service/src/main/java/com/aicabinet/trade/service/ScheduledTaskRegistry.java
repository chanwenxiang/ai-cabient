package com.aicabinet.trade.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 定时任务注册表：任务元数据与手动触发执行入口。
 * <p>action 指向各定时任务自身（含执行守卫），保证手动触发与自动调度走同一套
 * 启停开关 + 分布式锁 + 执行记录。</p>
 */
@Component
public class ScheduledTaskRegistry {

    /**
     * xxlManaged=true：开启 XXL-JOB 后内置 {@code @Scheduled} 经 tryBegin 让位，
     * 由调度中心统一触发（见 {@link XxlJobManagedTasks}）。
     */
    public record TaskDescriptor(String key, String name, String group, String scheduleDesc,
                                 long leaseSeconds, boolean xxlManaged, Runnable action) {
    }

    private final Map<String, TaskDescriptor> tasks = new LinkedHashMap<>();

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
                                 SessionService sessionService,
                                 CompensationTaskScheduler compensationTaskScheduler,
                                 PointsExpiryScheduler pointsExpiryScheduler,
                                 CouponExpiryReminderScheduler couponExpiryReminderScheduler,
                                 GrowthLogArchiveScheduler growthLogArchiveScheduler,
                                 SkuReviewScheduler skuReviewScheduler) {
        register("device-presence", "设备离线巡检", "DEVICE", "每 60 秒", 600,
                devicePresenceService::markStaleDevicesOffline);
        register("session-opening-expire", "开门超时会话清理", "TRADE", "每 30 秒", 600,
                sessionService::expireStaleOpeningSessions);
        register("session-restock-expire", "补货会话超时清理", "TRADE", "每 60 秒", 600,
                sessionService::expireStaleRestockShoppingSessions);
        register("session-recognizing-expire", "识别结算超时升级", "TRADE", "每 60 秒", 600,
                sessionService::expireStaleRecognizingSessions);
        register("ops-exception-scanner", "异常卡点扫描", "OPS", "每 30 秒", 600,
                opsExceptionScannerService::scan);
        register("compensation-process", "补偿任务处理", "SYSTEM", "每 30 秒", 600,
                compensationTaskScheduler::processCompensationTasks);
        register("compensation-retry", "补偿任务重试", "SYSTEM", "每 60 秒", 600,
                compensationTaskScheduler::retryFailedTransactions);
        register("replenishment-timeout", "补货超时收口", "WAREHOUSE", "每 60 秒", 600,
                replenishmentTimeoutScheduler::expireStaleCheckedInTasks);
        // 必须走 performConsistencyCheck（含 tryBegin/finish），不能直接 runConsistencyCheck，否则手动触发不写耗时/结果说明
        register("data-consistency", "数据一致性巡检", "OPS", "每 5 分钟", 900,
                dataConsistencyService::performConsistencyCheck);
        register("unpaid-cancel", "未付订单自动取消", "TRADE", "每 15 分钟", 600,
                unpaidOrderScheduler::autoCancelExpired);
        register("recharge-cancel", "充值单自动取消", "TRADE", "每 5 分钟", 600,
                rechargeOrderScheduler::autoCancelExpired);
        register("device-auto-unlock", "稳定在线自动解锁", "DEVICE", "每 5 分钟", 600,
                deviceAvailabilityJobScheduler::autoUnlockFallback);
        register("merchant-notify", "商户工作台通知", "MERCHANT", "每 15 分钟", 600,
                merchantWorkbenchNotifyScheduler::pushWorkbenchAlerts);
        register("dispute-sla", "争议 SLA 巡检", "OPS", "每 15 分钟", 600,
                disputeSlaScheduler::checkDisputeSla);
        register("profit-sharing-retry", "分账重试", "FINANCE", "每 15 分钟", 600,
                profitSharingRetryScheduler::retryFailedSplits);
        register("expiry-alert", "库存临期预警", "WAREHOUSE", "每 60 分钟", 600,
                expiryAlertScheduler::scanExpiry);
        register("reconciliation", "每日对账", "FINANCE", "每日 01:30", 1800,
                () -> reconciliationScheduler.runDailyReconciliation());
        register("line-commission", "线长佣金入账", "FINANCE", "每日 00:20", 1800,
                lineCommissionJob::postDailyCommission);
        register("finance-margin", "财务保证金固化", "FINANCE", "每日 00:05", 1800,
                financeMarginLockScheduler::solidifyYesterday);
        register("coupon-expire", "优惠券过期处理", "MARKETING", "每日 02:00", 600,
                couponService::expireOverdueCoupons);
        register("points-expiry", "积分过期管理", "MARKETING", "每 6 小时", 600,
                pointsExpiryScheduler::scan);
        register("coupon-expiry-remind", "优惠券临期提醒", "MARKETING", "每 6 小时", 600,
                couponExpiryReminderScheduler::scan);
        register("growth-log-archive", "增长日志归档", "SYSTEM", "每日 03:00", 600,
                growthLogArchiveScheduler::archive);
        register("sku-review-daily", "选品诊断每日刷新", "MARKETING", "每日 04:00", 600,
                skuReviewScheduler::scan);
        register("sla-snapshot", "SLA 日快照", "OPS", "每日 00:05", 600,
                slaMetricsService::snapshotDaily);
        register("kpi-snapshot", "设备可用性 KPI 快照", "OPS", "每日 01:10", 600,
                deviceAvailabilityJobScheduler::kpiSnapshotFallback);
    }

    public Optional<TaskDescriptor> get(String key) {
        return Optional.ofNullable(tasks.get(key));
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
