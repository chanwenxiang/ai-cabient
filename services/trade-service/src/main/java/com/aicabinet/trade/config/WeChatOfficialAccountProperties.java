package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 微信公众号服务号模板消息配置；默认关闭，认证后可独立启用。 */
@ConfigurationProperties(prefix = "aicabinet.wechat-official-account")
public record WeChatOfficialAccountProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String templateId,
        String url,
        String redirectUri,
        String consumerMiniAppId,
        String consumerMiniPagePath,
        String merchantMiniAppId,
        String merchantMiniPagePath,
        String merchantDailyReportTemplateId,
        String refundTemplateId,
        String withdrawTemplateId,
        String withdrawFailedTemplateId,
        String disputeTemplateId,
        String brandName
) {
    public boolean isBindingConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret) && notBlank(redirectUri);
    }

    public boolean isConfigured() {
        return isBindingConfigured() && notBlank(templateId);
    }

    public boolean isMerchantDailyReportConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret)
                && notBlank(merchantDailyReportTemplateId) && notBlank(merchantMiniAppId);
    }

    /** 退款模板链可用性：同一服务号发消费者退款通知，跳转消费者小程序。 */
    public boolean isRefundConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret)
                && notBlank(refundTemplateId) && notBlank(consumerMiniAppId);
    }

    /** 售后处理模板链可用性：消费者争议提交/处理结果通知，跳转消费者小程序。 */
    public boolean isDisputeConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret)
                && notBlank(disputeTemplateId) && notBlank(consumerMiniAppId);
    }

    /** 提现模板链可用性：商户提现结果发商户操作员，跳转商户小程序。 */
    public boolean isWithdrawConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret)
                && notBlank(withdrawTemplateId) && notBlank(merchantMiniAppId);
    }

    /** 提现失败模板链可用性：仅终态失败时发，自动重试中不发。 */
    public boolean isWithdrawFailedConfigured() {
        return enabled && notBlank(appId) && notBlank(appSecret)
                && notBlank(withdrawFailedTemplateId) && notBlank(merchantMiniAppId);
    }

    public String resolveUrl() {
        return url == null ? "" : url.trim();
    }

    public String resolveRedirectUri() {
        return redirectUri == null ? "" : redirectUri.trim();
    }

    public String resolveConsumerMiniAppId() {
        return consumerMiniAppId == null ? "" : consumerMiniAppId.trim();
    }

    public String resolveConsumerMiniPagePath() {
        return firstNonBlank(consumerMiniPagePath, "pages/messages/messages");
    }

    public String resolveMerchantMiniAppId() {
        return merchantMiniAppId == null ? "" : merchantMiniAppId.trim();
    }

    public String resolveMerchantMiniPagePath() {
        return firstNonBlank(merchantMiniPagePath, "pages/home/home");
    }

    public String resolveMerchantDailyReportTemplateId() {
        return merchantDailyReportTemplateId == null ? "" : merchantDailyReportTemplateId.trim();
    }

    public String resolveRefundTemplateId() {
        return refundTemplateId == null ? "" : refundTemplateId.trim();
    }

    public String resolveWithdrawTemplateId() {
        return withdrawTemplateId == null ? "" : withdrawTemplateId.trim();
    }

    public String resolveWithdrawFailedTemplateId() {
        return withdrawFailedTemplateId == null ? "" : withdrawFailedTemplateId.trim();
    }

    public String resolveDisputeTemplateId() {
        return disputeTemplateId == null ? "" : disputeTemplateId.trim();
    }

    /** 卡片「商家名称/商户名称」字段固定值；未配时该字段不传（模板侧显示空）。 */
    public String resolveBrandName() {
        return brandName == null ? "" : brandName.trim();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
