package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayScore / agreement charge settings.
 * {@code mockEnabled} (PAYSCORE_MOCK_ENABLED) only allows mock charges when
 * {@code aicabinet.security.mock-enabled} is also true — never alone in production.
 */
@ConfigurationProperties(prefix = "aicabinet.payscore")
public record PayScoreProperties(
        boolean enabled,
        boolean mockEnabled,
        int minScore,
        boolean liveChargeEnabled,
        String chargeGatewayUrl,
        String chargeGatewayApiKey
) {
    public PayScoreProperties {
        if (minScore <= 0) {
            minScore = 550;
        }
    }

    public boolean hasChargeGateway() {
        return chargeGatewayUrl != null && !chargeGatewayUrl.isBlank()
                && chargeGatewayApiKey != null && !chargeGatewayApiKey.isBlank();
    }
}
