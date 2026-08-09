package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 补货路线规划配置。
 *
 * @param provider 路线距离来源：NEAREST=直线距离（默认）；GAODE=高德驾车距离矩阵
 * @param gaodeKey 高德 Web 服务 Key（provider=GAODE 时必填）
 */
@ConfigurationProperties(prefix = "aicabinet.replenishment.route")
public record RoutePlanningProperties(
        String provider,
        String gaodeKey
) {
    public RoutePlanningProperties() {
        this("NEAREST", "");
    }

    public boolean gaodeEnabled() {
        return "GAODE".equalsIgnoreCase(provider())
                && gaodeKey() != null
                && !gaodeKey().isBlank();
    }
}
