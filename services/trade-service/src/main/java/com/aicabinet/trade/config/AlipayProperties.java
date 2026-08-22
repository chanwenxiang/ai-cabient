package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.alipay")
public record AlipayProperties(
        boolean enabled,
        String appId,
        String privateKey,
        String alipayPublicKey,
        String gatewayUrl,
        String notifyUrl,
        String returnUrl,
        /** 个人产品码，如 GENERAL_WITHHOLDING / CYCLE_PAY_AUTH_P */
        String agreementProductCode,
        /** 协议签约异步通知；空则回落 notifyUrl */
        String agreementNotifyUrl,
        /** 签约场景码，空则 DEFAULT */
        String agreementSignScene
) {
    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank()
                && privateKey != null && !privateKey.isBlank()
                && alipayPublicKey != null && !alipayPublicKey.isBlank();
    }

    public String resolvedAgreementNotifyUrl() {
        if (agreementNotifyUrl != null && !agreementNotifyUrl.isBlank()) {
            return agreementNotifyUrl.trim();
        }
        return notifyUrl;
    }

    public String resolvedAgreementProductCode() {
        if (agreementProductCode == null || agreementProductCode.isBlank()) {
            return "GENERAL_WITHHOLDING";
        }
        return agreementProductCode.trim();
    }

    public String resolvedAgreementSignScene() {
        if (agreementSignScene == null || agreementSignScene.isBlank()) {
            return "DEFAULT";
        }
        return agreementSignScene.trim();
    }
}
