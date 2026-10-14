package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.auth")
// ⚠️ 铁律 71 家族：@ConfigurationProperties record 只允许一个（canonical）构造器。
// 历史教训：曾加过 7 参便捷构造器，Spring 构造器绑定在多构造器下报
// "Failed to instantiate: No default constructor found"，容器无限重启。
// 测试需要默认宽限期时传 AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS。
public record AuthProperties(
        String jwtSecret,
        long expirationSeconds,
        boolean cookieEnabled,
        boolean cookieSecure,
        int loginMaxFailures,
        int loginLockMinutes,
        SmsProperties sms,
        /** 过期 token 仅允许 refresh 的宽限期（秒）。0/负数回退默认 86400（24h）。 */
        long refreshGraceSeconds
) {
    /** 未显式配置 refresh-grace-seconds 时的默认宽限期：24 小时。 */
    public static final long DEFAULT_REFRESH_GRACE_SECONDS = 86400L;

    public AuthProperties {
        if (refreshGraceSeconds <= 0) {
            refreshGraceSeconds = DEFAULT_REFRESH_GRACE_SECONDS;
        }
    }
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
