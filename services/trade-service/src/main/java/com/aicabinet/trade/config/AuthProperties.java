package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.auth")
public record AuthProperties(
        String jwtSecret,
        long expirationSeconds,
        boolean cookieEnabled,
        boolean cookieSecure,
        SmsProperties sms
) {
    public record SmsProperties(
            String mockCode,
            int ttlSeconds,
            String webhookUrl
    ) {
        public boolean hasWebhook() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }
    }
}
