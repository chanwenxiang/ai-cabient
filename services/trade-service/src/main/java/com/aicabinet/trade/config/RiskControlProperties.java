package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.risk")
public record RiskControlProperties(
        boolean enabled,
        int maxOpensPerHour,
        int maxDisputesPer7Days
) {
    public RiskControlProperties {
        if (maxOpensPerHour <= 0) {
            maxOpensPerHour = 5;
        }
        if (maxDisputesPer7Days <= 0) {
            maxDisputesPer7Days = 3;
        }
    }
}
