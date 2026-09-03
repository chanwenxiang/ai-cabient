package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 实名二要素核验（生产对接外部服务；dev/mock 跳过）。 */
@ConfigurationProperties(prefix = "aicabinet.identity-verify")
public record IdentityVerifyProperties(
        String baseUrl,
        String apiKey
) {
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
