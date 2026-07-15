package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.profit-sharing")
public record ProfitSharingProperties(
        boolean enabled,
        boolean retryEnabled,
        int retryBatchSize
) {
    public ProfitSharingProperties {
        if (retryBatchSize <= 0) {
            retryBatchSize = 20;
        }
    }
}
