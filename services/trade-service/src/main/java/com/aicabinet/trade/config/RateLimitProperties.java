package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 业务写接口用户维度限流（与短信/登录限流互补）。
 * <p>开门另有 {@link RiskControlProperties#maxOpensPerHour()} 业务风控；
 * 本配置对会话创建 / 补货开门做 Redis 小时窗口限流，防刷。
 * 开门同时按用户与设备双计数，避免单用户多柜机刷门绕过用户维度上限。
 */
@ConfigurationProperties(prefix = "aicabinet.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int maxCouponClaimsPerHour,
        @DefaultValue("30") int maxOrderPaysPerHour,
        @DefaultValue("20") int maxSessionCreatesPerHour,
        @DefaultValue("30") int maxOpenDoorsPerHour,
        @DefaultValue("60") int maxOpenDoorsPerDevicePerHour
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
        if (maxOpenDoorsPerDevicePerHour <= 0) {
            maxOpenDoorsPerDevicePerHour = 60;
        }
    }
}
