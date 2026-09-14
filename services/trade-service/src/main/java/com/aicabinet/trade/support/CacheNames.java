package com.aicabinet.trade.support;

/**
 * 统一缓存分组名与 TTL（S-P2-4）。
 * <p>调用 {@link CacheService#get}/{@link CacheService#evict} 时必须使用本类常量，
 * 禁止业务代码散落魔法字符串与裸毫秒数。</p>
 */
public final class CacheNames {

    /** 工作台 KPI / 统计：短缓存，偏实时。 */
    public static final String DASHBOARD_STATS = "dashboard:stats";
    public static final String DASHBOARD_WORKBENCH = "dashboard:workbench";
    /** 趋势类：稍长缓存，减轻重查询。 */
    public static final String DASHBOARD_TREND = "dashboard:trend";
    public static final String DASHBOARD_CHANNELS = "dashboard:channels";
    /** 下拉参照类。 */
    public static final String ADMIN_DEVICES_REF = "admin:devices:ref";
    /** SKU 列表相关失效前缀（写路径 evict）。 */
    public static final String ADMIN_SKUS = "admin:skus";

    /** 约 30s：仪表盘热点。 */
    public static final long TTL_SHORT_MS = 30_000L;
    /** 约 60s：趋势 / 参照。 */
    public static final long TTL_MEDIUM_MS = 60_000L;
    /** 约 5min：默认（{@link CacheService#get(String, String, java.util.function.Supplier)}）。 */
    public static final long TTL_DEFAULT_MS = 300_000L;

    private CacheNames() {
    }
}
