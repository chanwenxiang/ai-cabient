package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.wechat-miniapp")
public record WeChatMiniAppProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String subscribeTemplateId,
        String notifyPage
) {
    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    public boolean subscribeConfigured() {
        return isConfigured() && subscribeTemplateId != null && !subscribeTemplateId.isBlank();
    }

    public String resolveNotifyPage() {
        return notifyPage != null && !notifyPage.isBlank()
                ? notifyPage : "pages/alerts/alerts";
    }
}
