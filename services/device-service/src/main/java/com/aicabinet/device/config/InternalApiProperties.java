package com.aicabinet.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.internal-api")
public record InternalApiProperties(String key) {

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }
}
