package com.aicabinet.trade.service;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 资金 / 对账等 XXL-JOB 入口：与 {@link ScheduledTaskRegistry} 同一套 action（含 tryBegin/finish）。
 * <p>通用 handler {@code runScheduledTask} 的 JobParam 填 taskKey；另提供具名 handler 便于控制台配置。</p>
 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.xxljob", name = "enabled", havingValue = "true")
public class ScheduledTaskXxlJobHandler {

    private final ScheduledTaskRegistry registry;

    public ScheduledTaskXxlJobHandler(ScheduledTaskRegistry registry) {
        this.registry = registry;
    }

    /** JobParam = scheduled_task.task_key，例如 unpaid-cancel。 */
    @XxlJob("runScheduledTask")
    public void runScheduledTask() {
        runKey(XxlJobHelper.getJobParam());
    }

    @XxlJob("unpaidCancelJob")
    public void unpaidCancelJob() {
        runKey("unpaid-cancel");
    }

    @XxlJob("rechargeCancelJob")
    public void rechargeCancelJob() {
        runKey("recharge-cancel");
    }

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

    @XxlJob("dataConsistencyJob")
    public void dataConsistencyJob() {
        runKey("data-consistency");
    }

    @XxlJob("couponExpireJob")
    public void couponExpireJob() {
        runKey("coupon-expire");
    }

    @XxlJob("pointsExpiryJob")
    public void pointsExpiryJob() {
        runKey("points-expiry");
    }

    @XxlJob("deviceStableOnlineAutoUnlockJob")
    public void deviceStableOnlineAutoUnlockJob() {
        runKey("device-auto-unlock");
    }

    @XxlJob("deviceAvailabilityKpiDailyJob")
    public void deviceAvailabilityKpiDailyJob() {
        runKey("kpi-snapshot");
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
