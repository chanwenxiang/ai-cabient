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
        String merchantAppSecret,
        String miniprogramState
) {
    /** subscribe/send 的 miniprogram_state 合法值（微信文档：developer / trial / formal）。 */
    private static final java.util.Set<String> MINIPROGRAM_STATES =
            java.util.Set.of("developer", "trial", "formal");

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

    /**
     * 消费者订阅消息模板：🔴 fail-closed（MA-384 复核发现）——旧实现在消费者模板缺失时
     * 回落到商户模板，但两端是独立 appid、模板 id 不通用，发给 {@code MiniAppTarget.CONSUMER}
     * 必被微信回 400（invalid template_id）；而生产 example 里消费者模板默认就是空，
     * 于是「催付」一直静默失败只留审计。宁可不发（调用方走短信兜底并报出未配置），
     * 也不拿商户模板冒充——回退等于把一个必失败的请求伪装成「已发送」。
     */
    public String resolveConsumerTemplateId() {
        return consumerSubscribeTemplateId != null && !consumerSubscribeTemplateId.isBlank()
                ? consumerSubscribeTemplateId
                : null;
    }

    /** 消费者模板是否可用（调用方据此决定是否跳过微信、走短信兜底并提示运营）。 */
    public boolean hasConsumerTemplate() {
        return resolveConsumerTemplateId() != null;
    }

    public String resolveConsumerNotifyPage() {
        return consumerNotifyPage != null && !consumerNotifyPage.isBlank()
                ? consumerNotifyPage : "pages/messages/messages";
    }

    /**
     * 订阅消息点开后落到哪个小程序版本（微信 subscribe/send 的 {@code miniprogram_state}）。
     *
     * <p>🔴 MA-372：此前该值在 {@code WeChatMiniAppClient} 里**写死 "developer"**，生产用户收到
     * 催付/告警消息点开跳开发版——普通微信用户根本打不开，等于消息白发。改动前**无法**用任何
     * 环境变量覆盖，属配置缺失而非误配。
     *
     * <p>默认 {@code formal}（线上版）：发给真实用户的消息必须能打开。体验版验收期可配
     * {@code trial}，本地开发可配 {@code developer}。非法值一律回落 {@code formal}——宁可按线上版
     * 落地，也不能落到用户打不开的版本；配置写错不该静默生成废消息。
     */
    public String resolveMiniprogramState() {
        String value = miniprogramState == null ? "" : miniprogramState.trim().toLowerCase(java.util.Locale.ROOT);
        return MINIPROGRAM_STATES.contains(value) ? value : "formal";
    }
}
