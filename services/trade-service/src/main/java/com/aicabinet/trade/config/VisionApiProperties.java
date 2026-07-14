package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.vision-api")
public record VisionApiProperties(String key) {

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }
}
