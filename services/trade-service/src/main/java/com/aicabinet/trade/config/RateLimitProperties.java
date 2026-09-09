package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务写接口用户维度限流（与短信/登录限流互补；开门仍走 {@link RiskControlProperties}）。
 */
@ConfigurationProperties(prefix = "aicabinet.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int maxCouponClaimsPerHour,
        int maxOrderPaysPerHour
) {
    public RateLimitProperties {
        if (maxCouponClaimsPerHour <= 0) {
            maxCouponClaimsPerHour = 20;
        }
        if (maxOrderPaysPerHour <= 0) {
            maxOrderPaysPerHour = 30;
        }
    }
}
