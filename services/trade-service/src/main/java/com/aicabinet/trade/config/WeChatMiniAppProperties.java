package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.wechat-miniapp")
public record WeChatMiniAppProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String subscribeTemplateId,
        String notifyPage,
        String consumerSubscribeTemplateId,
        String consumerNotifyPage
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

    /** 消费者订阅消息模板：独立配置，缺省回退商户模板。 */
    public String resolveConsumerTemplateId() {
        return consumerSubscribeTemplateId != null && !consumerSubscribeTemplateId.isBlank()
                ? consumerSubscribeTemplateId
                : subscribeTemplateId;
    }

    public String resolveConsumerNotifyPage() {
        return consumerNotifyPage != null && !consumerNotifyPage.isBlank()
                ? consumerNotifyPage : "pages/messages/messages";
    }
}
