package com.aicabinet.trade.service;

import java.util.Set;

/**
 * 由 XXL-JOB 接管的任务清单（多实例 / 资金相关优先）。
 * <p>开启 {@code aicabinet.xxljob.enabled=true} 后：内置 {@code @Scheduled} 经
 * {@link ScheduledTaskService#tryBegin} 自动让位；调度中心按 JobHandler 触发同一套业务。</p>
 * <p>高频会话/设备巡检等不在此集合，仍由 Spring + 分布式锁执行。</p>
 */
public final class XxlJobManagedTasks {

    /** 与运营「定时任务」taskKey、调度中心 executor_param / 专用 handler 对齐。 */
    public static final Set<String> KEYS = Set.of(
            "unpaid-cancel",
            "recharge-cancel",
            "profit-sharing-retry",
            "reconciliation",
            "line-commission",
            "finance-margin",
            "data-consistency",
            "coupon-expire",
            "points-expiry",
            "device-auto-unlock",
            "kpi-snapshot"
    );

    private XxlJobManagedTasks() {
    }

    public static boolean isManaged(String taskKey) {
        return taskKey != null && KEYS.contains(taskKey);
    }
}
