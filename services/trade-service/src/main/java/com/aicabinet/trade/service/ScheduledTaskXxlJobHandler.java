package com.aicabinet.trade.service;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * XXL-JOB 入口：与 {@link ScheduledTaskRegistry} 同一套 action（含 tryBegin/finish）。
 * <p>业务定时任务全量托管（见 {@link XxlJobManagedTasks}），每个任务一个具名 handler，
 * 便于调度台列表直接可读、且杜绝 {@code executor_param} 被误改导致跑错任务。</p>
 * <p>通用 handler {@code runScheduledTask} 的 JobParam 填 taskKey；具名 handler 是排期的默认入口。</p>
 * <p>新增任务时，这里必须同步补一个具名 handler，否则 {@code scripts/check-xxl-job-wiring.mjs} 失败。</p>
 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.xxljob", name = "enabled", havingValue = "true")
public class ScheduledTaskXxlJobHandler {

    private final ScheduledTaskRegistry registry;

    public ScheduledTaskXxlJobHandler(ScheduledTaskRegistry registry) {
        this.registry = registry;
    }

    /** JobParam = scheduled_task.task_key，例如 unpaid-cancel（具名 handler 未覆盖时的备用入口）。 */
    @XxlJob("runScheduledTask")
    public void runScheduledTask() {
        runKey(XxlJobHelper.getJobParam());
    }

    // ── 交易会话与订单 ──────────────────────────────────────────────────────

    @XxlJob("unpaidCancelJob")
    public void unpaidCancelJob() {
        runKey("unpaid-cancel");
    }

    @XxlJob("rechargeCancelJob")
    public void rechargeCancelJob() {
        runKey("recharge-cancel");
    }

    @XxlJob("sessionOpeningExpireJob")
    public void sessionOpeningExpireJob() {
        runKey("session-opening-expire");
    }

    @XxlJob("sessionRestockExpireJob")
    public void sessionRestockExpireJob() {
        runKey("session-restock-expire");
    }

    @XxlJob("sessionDoorOpenExpireJob")
    public void sessionDoorOpenExpireJob() {
        runKey("session-door-open-expire");
    }

    @XxlJob("sessionRecognizingExpireJob")
    public void sessionRecognizingExpireJob() {
        runKey("session-recognizing-expire");
    }

    // ── 设备 ────────────────────────────────────────────────────────────────

    @XxlJob("devicePresenceOfflineJob")
    public void devicePresenceOfflineJob() {
        runKey("device-presence");
    }

    @XxlJob("deviceStableOnlineAutoUnlockJob")
    public void deviceStableOnlineAutoUnlockJob() {
        runKey("device-auto-unlock");
    }

    @XxlJob("tempPlanApplyJob")
    public void tempPlanApplyJob() {
        runKey("temp-plan");
    }

    // ── 资金 / 财务 ─────────────────────────────────────────────────────────

    @XxlJob("profitSharingRetryJob")
    public void profitSharingRetryJob() {
        runKey("profit-sharing-retry");
    }

    @XxlJob("reconciliationJob")
    public void reconciliationJob() {
        runKey("reconciliation");
    }

    @XxlJob("lineCommissionJob")
    public void lineCommissionJob() {
        runKey("line-commission");
    }

    @XxlJob("financeMarginJob")
    public void financeMarginJob() {
        runKey("finance-margin");
    }

    @XxlJob("opsFeeBillMonthlyJob")
    public void opsFeeBillMonthlyJob() {
        runKey("ops-fee-bill-monthly");
    }

    // ── 营销 ────────────────────────────────────────────────────────────────

    @XxlJob("couponExpireJob")
    public void couponExpireJob() {
        runKey("coupon-expire");
    }

    @XxlJob("couponExpiryRemindJob")
    public void couponExpiryRemindJob() {
        runKey("coupon-expiry-remind");
    }

    @XxlJob("pointsExpiryJob")
    public void pointsExpiryJob() {
        runKey("points-expiry");
    }

    @XxlJob("skuReviewDailyJob")
    public void skuReviewDailyJob() {
        runKey("sku-review-daily");
    }

    // ── 运维 / 系统 ─────────────────────────────────────────────────────────

    @XxlJob("dataConsistencyJob")
    public void dataConsistencyJob() {
        runKey("data-consistency");
    }

    @XxlJob("opsExceptionScannerJob")
    public void opsExceptionScannerJob() {
        runKey("ops-exception-scanner");
    }

    @XxlJob("compensationProcessJob")
    public void compensationProcessJob() {
        runKey("compensation-process");
    }

    @XxlJob("compensationRetryJob")
    public void compensationRetryJob() {
        runKey("compensation-retry");
    }

    @XxlJob("merchantNotifyJob")
    public void merchantNotifyJob() {
        runKey("merchant-notify");
    }

    @XxlJob("disputeSlaJob")
    public void disputeSlaJob() {
        runKey("dispute-sla");
    }

    @XxlJob("replenishmentTimeoutJob")
    public void replenishmentTimeoutJob() {
        runKey("replenishment-timeout");
    }

    @XxlJob("expiryAlertJob")
    public void expiryAlertJob() {
        runKey("expiry-alert");
    }

    @XxlJob("growthLogArchiveJob")
    public void growthLogArchiveJob() {
        runKey("growth-log-archive");
    }

    @XxlJob("slaSnapshotJob")
    public void slaSnapshotJob() {
        runKey("sla-snapshot");
    }

    @XxlJob("deviceAvailabilityKpiDailyJob")
    public void deviceAvailabilityKpiDailyJob() {
        runKey("kpi-snapshot");
    }

    @XxlJob("riskAutoDispositionJob")
    public void riskAutoDispositionJob() {
        runKey("risk-auto-disposition");
    }

    private void runKey(String taskKey) {
        if (!StringUtils.hasText(taskKey)) {
            XxlJobHelper.handleFail("jobParam / taskKey 为空");
            return;
        }
        String key = taskKey.trim();
        if (!XxlJobManagedTasks.isManaged(key)) {
            XxlJobHelper.handleFail("非 XXL 托管任务: " + key);
            return;
        }
        ScheduledTaskRegistry.TaskDescriptor descriptor = registry.get(key).orElse(null);
        if (descriptor == null) {
            XxlJobHelper.handleFail("任务未注册: " + key);
            return;
        }
        try {
            descriptor.action().run();
            XxlJobHelper.log("scheduled task finished key={} name={}", key, descriptor.name());
            XxlJobHelper.handleSuccess("ok:" + key);
        } catch (Exception e) {
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
