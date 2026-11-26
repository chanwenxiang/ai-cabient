package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商户「每日经营日报」微信消息配置（MA-377）：服务号与商户小程序订阅共用关键词名，模板 ID 分别配置。
 *
 * <p><b>为什么单独一套配置，不塞进 {@link WeChatMiniAppProperties}</b>：后者是「双小程序凭证 +
 * 通用订阅模板」的身份配置，改一次要动所有构造点与生产启动校验；日报属于**业务通知**，
 * 模板 id、落地页、关键词名都要能独立开关与试错（模板被微信驳回就要换 id），单独成组
 * 才能做到「改 .env 即生效、不用碰代码」。
 *
 * <p><b>为什么关键词名也是配置</b>：订阅消息的 data 键名（{@code amount1}/{@code number2}…）
 * 由**商户小程序后台选定的模板**决定，代码里写死就等于把「选哪个模板」焊死的代码里——
 * 换个模板就得重新发版。这里默认值只是常见「经营数据」模板的键名，运营在后台看到实际
 * 键名不同时改 env 即可。
 *
 * <p>🔴 fail-closed：{@link #enabled()} 关或 {@code template-id} 为空一律不发。宁可让商户
 * 只看到站内信，也不能发一条微信报 400 的废消息（微信侧失败只返回 errcode，商户端无感）。
 */
@ConfigurationProperties(prefix = "aicabinet.merchant-daily-report")
public record MerchantDailyReportProperties(
        boolean enabled,
        String templateId,
        String page,
        String salesKey,
        String ordersKey,
        String avgKey,
        String officialSalesKey,
        String officialMerchantNameKey,
        String officialTimeKey
) {
    /** 默认落地页：商户端首页（第一屏就是「今日营收 / 今日订单」，点开即看数）。 */
    static final String DEFAULT_PAGE = "pages/home/home";
    static final String DEFAULT_SALES_KEY = "amount1";
    static final String DEFAULT_ORDERS_KEY = "number2";
    static final String DEFAULT_AVG_KEY = "amount3";
    // 服务号「账单结算成功通知」模板键名（2026-11-26 选定，get_all_private_template 核实）：
    // amount9=结算金额、thing16=商户名称、time11=结算时间。
    static final String DEFAULT_OFFICIAL_SALES_KEY = "amount9";
    static final String DEFAULT_OFFICIAL_MERCHANT_NAME_KEY = "thing16";
    static final String DEFAULT_OFFICIAL_TIME_KEY = "time11";

    /** 关停态（未配模板）：单测与「只想验站内信」的场景用，语义等同 {@code enabled=false}。 */
    public static MerchantDailyReportProperties disabled() {
        return new MerchantDailyReportProperties(false, "", "", "", "", "", "", "", "");
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred.trim();
    }

    /** 是否可以发（开关 + 模板 id 齐备）。 */
    public boolean configured() {
        return enabled && !isBlank(templateId);
    }

    public String resolveTemplateId() {
        return isBlank(templateId) ? "" : templateId.trim();
    }

    public String resolvePage() {
        return firstNonBlank(page, DEFAULT_PAGE);
    }

    public String resolveSalesKey() {
        return firstNonBlank(salesKey, DEFAULT_SALES_KEY);
    }

    public String resolveOrdersKey() {
        return firstNonBlank(ordersKey, DEFAULT_ORDERS_KEY);
    }

    public String resolveAvgKey() {
        return firstNonBlank(avgKey, DEFAULT_AVG_KEY);
    }

    /** 服务号日报模板键名（与商户小程序订阅模板键名分开配置，两套模板键名不同）。 */
    public String resolveOfficialSalesKey() {
        return firstNonBlank(officialSalesKey, DEFAULT_OFFICIAL_SALES_KEY);
    }

    public String resolveOfficialMerchantNameKey() {
        return firstNonBlank(officialMerchantNameKey, DEFAULT_OFFICIAL_MERCHANT_NAME_KEY);
    }

    public String resolveOfficialTimeKey() {
        return firstNonBlank(officialTimeKey, DEFAULT_OFFICIAL_TIME_KEY);
    }
}
