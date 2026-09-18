package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;

import java.util.Set;

/**
 * 由 XXL-JOB 接管的任务清单。
 * <p><b>全量托管（2026-09-16 起）。</b>业务定时任务一律由调度中心统一触发，理由是生产为多实例部署：
 * Spring {@code @Scheduled} 在集群里靠 Redis 锁只能保证「只有一台真跑」，代价是<em>每台每周期都空转抢一次锁</em>；
 * 交给 XXL 后由调度中心单选派发，这层浪费消失，且失败重试 / 路由 / 触发历史都在调度台可见。</p>
 * <p>开启 {@code aicabinet.xxljob.enabled=true} 后：内置 {@code @Scheduled} 经
 * {@link ScheduledTaskService#tryBegin} 自动让位；调度中心按 JobHandler 触发同一套业务。
 * 关闭该开关则全部回退到 Spring 常驻调度 —— 这是调度中心不可用时的逃生门，故 {@code @Scheduled} 注解一律保留。</p>
 *
 * <p><b>刻意排除的 2 个任务（不是遗漏，托管后会失效）：</b></p>
 * <ul>
 *   <li>{@code scheduled-task-stale-monitor} 超期看护 —— 它是「检测 XXL 是否失效」的装置。一旦也交给 XXL，
 *       调度中心故障时它会与被看护任务<em>同时</em>停跑，唯一能报警的东西没了。由
 *       {@code scripts/check-xxl-job-wiring.mjs} 规则拦截。</li>
 *   <li>{@code cache-purge} 本机缓存清理 —— 清理的是本进程 {@code ConcurrentHashMap}，必须每个实例自己清。
 *       它不借分布式锁、不进运营台，也就无需托管（见 {@code CacheConfig}）。</li>
 * </ul>
 *
 * <p>调度时区统一 {@link ScheduleZones#ZONE_ID}；cron 见 {@link ScheduleZones#XXL_CRON_BY_TASK}
 *（与 {@code infra/xxl-job/seed_aicabinet_jobs.sql} 一致）。</p>
 */
public final class XxlJobManagedTasks {

    /**
     * 与运营「定时任务」taskKey、调度中心 executor_param / 专用 handler 对齐。
     * <p><b>新增任务必须同步七处</b>（漏任何一处任务都会静默不跑或不可见，各处的必要性都有门禁实证）：
     * <ol>
     *   <li>本清单 {@code KEYS}；</li>
     *   <li>{@code ScheduledTaskXxlJobHandler} 的具名 {@code @XxlJob("…Job")} handler；</li>
     *   <li>{@link ScheduleZones#XXL_CRON_BY_TASK}（XXL 侧 cron）；</li>
     *   <li>{@link ScheduleZones#MAX_SILENCE_BY_TASK}（超期看护阈值，缺条目看护会静默跳过）；</li>
     *   <li>{@code ScheduledTaskRegistry} 的 {@code register(key, …)}（否则调度中心派发进来
     *       {@code registry.get(key)} 为空 → {@code handleFail「任务未注册」}）；</li>
     *   <li>{@code db/migration} 的 {@code scheduled_task} 登记行（否则 {@code finish()}
     *       静默丢弃执行记录，运营台看不见、不能启停）；</li>
     *   <li>{@code infra/xxl-job/seed_aicabinet_jobs.sql} 的排期行。</li>
     * </ol>
     * ①~④⑦ 由 {@code scripts/check-xxl-job-wiring.mjs} 校验，⑤⑥ 另由
     * {@code scripts/check-scheduled-task-seed.mjs} 校验。</p>
     */
    public static final Set<String> KEYS = Set.of(
            // 交易会话与订单
            "unpaid-cancel",
            "recharge-cancel",
            "session-opening-expire",
            "session-restock-expire",
            "session-door-open-expire",
            "session-recognizing-expire",
            // 设备
            "device-presence",
            "device-auto-unlock",
            "temp-plan",
            // 资金 / 财务
            "profit-sharing-retry",
            "reconciliation",
            "line-commission",
            "finance-margin",
            "ops-fee-bill-monthly",
            "withdraw-paying-timeout",
            // 营销
            "coupon-expire",
            "coupon-expiry-remind",
            "points-expiry",
            "sku-review-daily",
            // 运维 / 系统
            "data-consistency",
            "ops-exception-scanner",
            "compensation-process",
            "compensation-retry",
            "merchant-notify",
            "dispute-sla",
            "replenishment-timeout",
            "expiry-alert",
            "growth-log-archive",
            "sla-snapshot",
            "kpi-snapshot",
            "risk-auto-disposition"
    );

    private XxlJobManagedTasks() {
    }

    public static boolean isManaged(String taskKey) {
        return taskKey != null && KEYS.contains(taskKey);
    }

    /** XXL 推荐 cron；未知 key 返回 null。 */
    public static String recommendedXxlCron(String taskKey) {
        return ScheduleZones.XXL_CRON_BY_TASK.get(taskKey);
    }
}
