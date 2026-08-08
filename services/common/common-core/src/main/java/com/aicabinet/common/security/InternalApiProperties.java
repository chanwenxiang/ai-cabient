package com.aicabinet.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内部 API 密钥配置（trade-service / device-service 共用）。 */
@ConfigurationProperties(prefix = "aicabinet.internal-api")
public record InternalApiProperties(String key) {

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }
}
