package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.minio")
public record MinioProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucket,
        int presignExpirySeconds
) {
    public MinioProperties {
        if (presignExpirySeconds <= 0) {
            presignExpirySeconds = 3600;
        }
    }
}
