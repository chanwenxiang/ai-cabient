package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayScore / agreement charge settings.
 * {@code mockEnabled} (PAYSCORE_MOCK_ENABLED) only allows mock charges when
 * {@code aicabinet.security.mock-enabled} is also true — never alone in production.
 *
 * <p>{@code serviceId}：微信支付分「服务 ID」，由微信支付运营在产品权限审核通过后下发
 * （CB-064：分数门槛与风险金上限都在微信商户平台设，代码不判分）。
 * 未配置时微信免密签约走 fail-closed（501），与 {@code enabled=false} 同义。</p>
 */
@ConfigurationProperties(prefix = "aicabinet.payscore")
public record PayScoreProperties(
        boolean enabled,
        boolean mockEnabled,
        int minScore,
        boolean liveChargeEnabled,
        String chargeGatewayUrl,
        String chargeGatewayApiKey,
        String serviceId,
        String permissionNotifyUrl
) {
    public PayScoreProperties {
        if (minScore <= 0) {
            minScore = 550;
        }
    }

    public boolean hasChargeGateway() {
        return chargeGatewayUrl != null && !chargeGatewayUrl.isBlank()
                && chargeGatewayApiKey != null && !chargeGatewayApiKey.isBlank();
    }

    public boolean hasServiceId() {
        return serviceId != null && !serviceId.isBlank();
    }
}
