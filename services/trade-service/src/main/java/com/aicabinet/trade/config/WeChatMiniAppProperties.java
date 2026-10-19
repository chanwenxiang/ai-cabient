package com.aicabinet.trade.config;

import com.aicabinet.trade.wechat.MiniAppTarget;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 双小程序凭证（2026-10-19 双 appid 改造）：
 * <ul>
 *   <li>{@code consumerAppId/consumerAppSecret} —— 消费者小程序（WECHAT_CONSUMER_APP_ID/SECRET）</li>
 *   <li>{@code merchantAppId/merchantAppSecret} —— 商户小程序（WECHAT_MERCHANT_APP_ID/SECRET）</li>
 *   <li>{@code appId/appSecret} —— 旧的单组配置（WECHAT_MINIAPP_ID/SECRET），仅作回退：
 *       端专属配置缺省时沿用旧值，保证既有部署零改动兼容。</li>
 * </ul>
 *
 * <p>openid/access_token 按 appid 隔离：两对凭证指向两个独立小程序主体，不可共用。
 */
@ConfigurationProperties(prefix = "aicabinet.wechat-miniapp")
public record WeChatMiniAppProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String subscribeTemplateId,
        String notifyPage,
        String consumerSubscribeTemplateId,
        String consumerNotifyPage,
        String consumerAppId,
        String consumerAppSecret,
        String merchantAppId,
        String merchantAppSecret
) {
    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** 指定端的 appid；端专属配置缺省时回退旧单组 app-id。 */
    public String resolveAppId(MiniAppTarget target) {
        return target == MiniAppTarget.CONSUMER
                ? firstNonBlank(consumerAppId, appId)
                : firstNonBlank(merchantAppId, appId);
    }

    /** 指定端的 appSecret；回退规则同 {@link #resolveAppId}。 */
    public String resolveAppSecret(MiniAppTarget target) {
        return target == MiniAppTarget.CONSUMER
                ? firstNonBlank(consumerAppSecret, appSecret)
                : firstNonBlank(merchantAppSecret, appSecret);
    }

    /** 指定端是否已配齐（enabled + 该端 appid/secret 均非空，含旧值回退）。 */
    public boolean isConfigured(MiniAppTarget target) {
        return enabled && !isBlank(resolveAppId(target)) && !isBlank(resolveAppSecret(target));
    }

    /**
     * 整体配置判据：两端都已配齐（兼容语义——旧单组配置回退后两端同值，行为与改造前一致）。
     * 启动校验与运营台「订阅消息已启用」展示用；微信侧调用请用 {@link #isConfigured(MiniAppTarget)}。
     */
    public boolean isConfigured() {
        return isConfigured(MiniAppTarget.CONSUMER) && isConfigured(MiniAppTarget.MERCHANT);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
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
