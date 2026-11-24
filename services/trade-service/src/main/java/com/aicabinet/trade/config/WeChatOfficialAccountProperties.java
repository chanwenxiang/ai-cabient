package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 微信公众号服务号模板消息配置；默认关闭，认证后可独立启用。 */
@ConfigurationProperties(prefix = "aicabinet.wechat-official-account")
public record WeChatOfficialAccountProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String templateId,
        String url
) {
    public boolean isConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret) && notBlank(templateId);
    }

    public String resolveUrl() {
        return url == null ? "" : url.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
