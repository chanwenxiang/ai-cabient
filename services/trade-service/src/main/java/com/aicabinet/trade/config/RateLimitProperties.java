package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务写接口用户维度限流（与短信/登录限流互补）。
 * <p>开门另有 {@link RiskControlProperties#maxOpensPerHour()} 业务风控；
 * 本配置对会话创建 / 补货开门做 Redis 小时窗口限流，防刷。
 */
@ConfigurationProperties(prefix = "aicabinet.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int maxCouponClaimsPerHour,
        int maxOrderPaysPerHour,
        int maxSessionCreatesPerHour,
        int maxOpenDoorsPerHour
) {
    public RateLimitProperties {
        if (maxCouponClaimsPerHour <= 0) {
            maxCouponClaimsPerHour = 20;
        }
        if (maxOrderPaysPerHour <= 0) {
            maxOrderPaysPerHour = 30;
        }
        if (maxSessionCreatesPerHour <= 0) {
            maxSessionCreatesPerHour = 20;
        }
        if (maxOpenDoorsPerHour <= 0) {
            maxOpenDoorsPerHour = 30;
        }
    }

    /** 兼容旧测试与仅配置券/支付两项的局部构造。 */
    public RateLimitProperties(boolean enabled, int maxCouponClaimsPerHour, int maxOrderPaysPerHour) {
        this(enabled, maxCouponClaimsPerHour, maxOrderPaysPerHour, 20, 30);
    }
}
