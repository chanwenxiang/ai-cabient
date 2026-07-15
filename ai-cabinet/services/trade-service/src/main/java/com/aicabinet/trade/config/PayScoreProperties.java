package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.payscore")
public record PayScoreProperties(
        boolean enabled,
        boolean mockEnabled,
        int minScore,
        boolean liveChargeEnabled
) {
    public PayScoreProperties {
        if (minScore <= 0) {
            minScore = 550;
        }
    }
}
