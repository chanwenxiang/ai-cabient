package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.ops-monitoring")
public record OpsMonitoringProperties(
        boolean enabled,
        int doorOpenMinutes,
        int recognitionStuckMinutes,
        int settlementStuckMinutes
) {
    public OpsMonitoringProperties {
        if (doorOpenMinutes <= 0) doorOpenMinutes = 10;
        if (recognitionStuckMinutes <= 0) recognitionStuckMinutes = 3;
        if (settlementStuckMinutes <= 0) settlementStuckMinutes = 3;
    }
}
