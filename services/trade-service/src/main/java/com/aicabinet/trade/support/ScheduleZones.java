package com.aicabinet.trade.support;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

/**
 * 业务调度统一时区与 cron 约定（S-P2-7）。
 * <p>Spring {@code @Scheduled} 用 6 段 cron；XXL-JOB 控制台/种子用 Quartz 7 段（日/周位 {@code ?}）。
 * 二者语义必须对齐，且均按 {@link #ZONE_ID} 解释。</p>
 * <p><b>托管后以 XXL 侧 cron 为准</b>：调度中心是墙钟 cron，而 Spring 侧部分任务用的是
 * {@code fixedRate}（从启动时刻起算）。托管后这些任务的执行时刻变为固定墙钟点，与运营台
 * 展示的 {@code scheduleDesc} 一致 —— 顺带消除了「展示频率 ≠ 实际调度」的偏差。</p>
 */
public final class ScheduleZones {

    public static final String ZONE_ID = "Asia/Shanghai";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    /** 供 {@code @Scheduled(zone = ...)} / 配置占位默认值。 */
    public static final String ZONE_PROP = "${aicabinet.schedule.zone:" + ZONE_ID + "}";

    // ── Spring 6 段（内置 @Scheduled）──
    public static final String SPRING_CRON_RECON = "0 30 1 * * *";
    public static final String SPRING_CRON_LINE_COMMISSION = "0 20 0 * * *";
    public static final String SPRING_CRON_FINANCE_MARGIN = "0 5 0 * * *";
    public static final String SPRING_CRON_COUPON_EXPIRE = "0 0 2 * * *";
    public static final String SPRING_CRON_SLA_SNAPSHOT = "0 5 0 * * *";
    public static final String SPRING_CRON_KPI_SNAPSHOT = "0 10 1 * * *";

    /**
     * XXL 种子 cron（Quartz <b>6 段</b>：秒 分 时 日 月 周）：与 {@code infra/xxl-job/seed_aicabinet_jobs.sql} 对齐。
     * key = {@link com.aicabinet.trade.service.XxlJobManagedTasks} taskKey，必须逐一对应（门禁校验）。
     * <p>⚠️ <b>「日」与「周」不可同时限定</b>：指定了「日」（如月任务写 {@code 0 30 1 1 * ?}）时，
     * 「周」必须写 {@code ?}；反之亦然。两者都写实数或 {@code *} 会被 XXL 的 {@code CronExpression}
     * 直接拒绝（{@code storeExpressionVals} 抛错），现象是 admin 日志
     * {@code refreshNextValidTime error for job: jobId=…} 且该 job <b>永远不触发</b> ——
     * 实测 {@code ops-fee-bill-monthly} 曾写成 7 段 {@code 0 30 1 1 * * ?} 而停摆。
     * 该形态由 {@code scripts/check-xxl-job-wiring.mjs} 规则 3.10 静态拦截。</p>
     */
    public static final Map<String, String> XXL_CRON_BY_TASK = Map.ofEntries(
            // 交易会话与订单
            Map.entry("unpaid-cancel", "0 0/15 * * * ?"),
            Map.entry("recharge-cancel", "0 0/5 * * * ?"),
            Map.entry("session-opening-expire", "0/30 * * * * ?"),
            Map.entry("session-restock-expire", "0 0/1 * * * ?"),
            Map.entry("session-door-open-expire", "0 0/1 * * * ?"),
            Map.entry("session-recognizing-expire", "0 0/1 * * * ?"),
            // 设备
            Map.entry("device-presence", "0 0/1 * * * ?"),
            Map.entry("device-auto-unlock", "0 0/5 * * * ?"),
            Map.entry("temp-plan", "0 0/1 * * * ?"),
            // 资金 / 财务
            Map.entry("profit-sharing-retry", "0 0/15 * * * ?"),
            Map.entry("reconciliation", "0 30 1 * * ?"),
            Map.entry("line-commission", "0 20 0 * * ?"),
            Map.entry("finance-margin", "0 5 0 * * ?"),
            Map.entry("ops-fee-bill-monthly", "0 30 1 1 * ?"),
            // 营销
            Map.entry("coupon-expire", "0 0 2 * * ?"),
            Map.entry("coupon-expiry-remind", "0 0 0/6 * * ?"),
            Map.entry("points-expiry", "0 0 0/6 * * ?"),
            Map.entry("sku-review-daily", "0 0 4 * * ?"),
            // 运维 / 系统
            Map.entry("data-consistency", "0 0/5 * * * ?"),
            Map.entry("ops-exception-scanner", "0/30 * * * * ?"),
            Map.entry("compensation-process", "0/30 * * * * ?"),
            Map.entry("compensation-retry", "0 0/1 * * * ?"),
            Map.entry("merchant-notify", "0 0/15 * * * ?"),
            Map.entry("dispute-sla", "0 0/15 * * * ?"),
            Map.entry("replenishment-timeout", "0 0/1 * * * ?"),
            Map.entry("expiry-alert", "0 0 * * * ?"),
            Map.entry("growth-log-archive", "0 0 3 * * ?"),
            Map.entry("sla-snapshot", "0 5 0 * * ?"),
            Map.entry("kpi-snapshot", "0 10 1 * * ?"),
            Map.entry("risk-auto-disposition", "0 0/15 * * * ?")
    );

    /**
     * 托管任务「最大静默时长」：超过它仍没有推进 {@code scheduled_task.last_run_at} 即视为停跑。
     * <p>用于超期看护（{@code ScheduledTaskStaleMonitor}）。取值 = 调度周期 + 宽限，
     * 宽限至少留一个周期，避免单次 GC / 重启 / 慢查询造成误报。</p>
     * <p>日任务按 26 小时（跨时区/夏令时余量 + 1 个周期）。<b>月任务必须按 32 天</b> ——
     * 按日任务的 26 小时设，会每月被误报一次超期。</p>
     * <p>新增加托管任务时必须同时补条目，由 {@code scripts/check-xxl-job-wiring.mjs} 静态校验，缺失即失败。</p>
     */
    public static final Map<String, Duration> MAX_SILENCE_BY_TASK = Map.ofEntries(
            // 交易会话与订单（秒级 → 5~10 分钟；分钟级 → 3 倍周期）
            Map.entry("unpaid-cancel", Duration.ofMinutes(45)),                // 15min ×3
            Map.entry("recharge-cancel", Duration.ofMinutes(20)),              // 5min ×4
            Map.entry("session-opening-expire", Duration.ofMinutes(5)),        // 30s ×10
            Map.entry("session-restock-expire", Duration.ofMinutes(10)),       // 60s ×10
            Map.entry("session-door-open-expire", Duration.ofMinutes(10)),     // 60s ×10
            Map.entry("session-recognizing-expire", Duration.ofMinutes(10)),   // 60s ×10
            // 设备
            Map.entry("device-presence", Duration.ofMinutes(10)),              // 60s ×10
            Map.entry("device-auto-unlock", Duration.ofMinutes(20)),           // 5min ×4
            Map.entry("temp-plan", Duration.ofMinutes(10)),                    // 60s ×10
            // 资金 / 财务
            Map.entry("profit-sharing-retry", Duration.ofMinutes(45)),         // 15min ×3
            Map.entry("reconciliation", Duration.ofHours(26)),                 // 日 01:30 + 2.5h
            Map.entry("line-commission", Duration.ofHours(26)),                // 日 00:20 + 2h
            Map.entry("finance-margin", Duration.ofHours(26)),                 // 日 00:05 + 2h
            Map.entry("ops-fee-bill-monthly", Duration.ofDays(32)),            // 月 1 日 01:30 + 1 天
            // 营销
            Map.entry("coupon-expire", Duration.ofHours(26)),                  // 日 02:00 + 2h
            Map.entry("coupon-expiry-remind", Duration.ofHours(8)),            // 6h + 2h
            Map.entry("points-expiry", Duration.ofHours(8)),                   // 6h + 2h
            Map.entry("sku-review-daily", Duration.ofHours(26)),               // 日 04:00 + 2h
            // 运维 / 系统
            Map.entry("data-consistency", Duration.ofMinutes(20)),             // 5min ×4
            Map.entry("ops-exception-scanner", Duration.ofMinutes(5)),         // 30s ×10
            Map.entry("compensation-process", Duration.ofMinutes(5)),          // 30s ×10
            Map.entry("compensation-retry", Duration.ofMinutes(10)),           // 60s ×10
            Map.entry("merchant-notify", Duration.ofMinutes(45)),              // 15min ×3
            Map.entry("dispute-sla", Duration.ofMinutes(45)),                  // 15min ×3
            Map.entry("replenishment-timeout", Duration.ofMinutes(10)),        // 60s ×10
            Map.entry("expiry-alert", Duration.ofHours(3)),                    // 60min ×3
            Map.entry("growth-log-archive", Duration.ofHours(26)),             // 日 03:00 + 2h
            Map.entry("sla-snapshot", Duration.ofHours(26)),                   // 日 00:05 + 2h
            Map.entry("kpi-snapshot", Duration.ofHours(26)),                   // 日 01:10 + 2h
            Map.entry("risk-auto-disposition", Duration.ofMinutes(45))         // 15min ×3
    );

    private ScheduleZones() {
    }

    /** 运营台 scheduleDesc：附加时区，避免与服务器默认时区混淆。 */
    public static String desc(String plain) {
        return plain + " (" + ZONE_ID + ")";
    }
}
