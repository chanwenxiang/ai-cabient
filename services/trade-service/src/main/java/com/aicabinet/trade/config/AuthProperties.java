package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.auth")
public record AuthProperties(
        String jwtSecret,
        long expirationSeconds,
        boolean cookieEnabled,
        boolean cookieSecure,
        int loginMaxFailures,
        int loginLockMinutes,
        SmsProperties sms
) {
    public record SmsProperties(
            String mockCode,
            int ttlSeconds,
            String webhookUrl,
            /** webhook（默认）| aliyun | auto（有阿里云密钥则用阿里云） */
            String provider,
            String aliyunAccessKeyId,
            String aliyunAccessKeySecret,
            String aliyunSignName,
            String aliyunTemplateCode,
            String aliyunRegionId
    ) {
        public SmsProperties {
            if (provider == null || provider.isBlank()) {
                provider = "webhook";
            }
        }

        public boolean hasWebhook() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }
    }
}
