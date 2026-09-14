package com.aicabinet.trade.support;

import java.time.ZoneId;
import java.util.Map;

/**
 * 业务调度统一时区与 cron 约定（S-P2-7）。
 * <p>Spring {@code @Scheduled} 用 6 段 cron；XXL-JOB 控制台/种子用 Quartz 7 段（日/周位 {@code ?}）。
 * 二者语义必须对齐，且均按 {@link #ZONE_ID} 解释。</p>
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
     * XXL 种子 cron（Quartz）：与 {@code infra/xxl-job/seed_aicabinet_jobs.sql} 对齐。
     * key = {@link com.aicabinet.trade.service.XxlJobManagedTasks} taskKey。
     */
    public static final Map<String, String> XXL_CRON_BY_TASK = Map.ofEntries(
            Map.entry("unpaid-cancel", "0 0/15 * * * ?"),
            Map.entry("recharge-cancel", "0 0/5 * * * ?"),
            Map.entry("profit-sharing-retry", "0 0/15 * * * ?"),
            Map.entry("reconciliation", "0 30 1 * * ?"),
            Map.entry("line-commission", "0 20 0 * * ?"),
            Map.entry("finance-margin", "0 5 0 * * ?"),
            Map.entry("data-consistency", "0 0/5 * * * ?"),
            Map.entry("coupon-expire", "0 0 2 * * ?"),
            Map.entry("points-expiry", "0 0 0/6 * * ?"),
            Map.entry("device-auto-unlock", "0 0/5 * * * ?"),
            Map.entry("kpi-snapshot", "0 10 1 * * ?")
    );

    private ScheduleZones() {
    }

    /** 运营台 scheduleDesc：附加时区，避免与服务器默认时区混淆。 */
    public static String desc(String plain) {
        return plain + " (" + ZONE_ID + ")";
    }
}
