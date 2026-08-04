package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.wechat-pay")
public record WeChatPayProperties(
        boolean enabled,
        String appId,
        String mchId,
        String notifyUrl,
        /** API v3 密钥（32 字节，用于回调解密） */
        String apiV3Key,
        /** 商户 API 证书序列号 */
        String merchantSerialNo,
        /** 商户 API 私钥 PEM */
        String privateKey,
        /** 微信支付平台证书 PEM（用于验签回调；留空时可自动拉取） */
        String platformCert,
        /** 是否自动从 /v3/certificates 拉取平台证书 */
        boolean platformCertAutoFetch
) {
    public boolean isConfigured() {
        return enabled
                && notBlank(appId)
                && notBlank(mchId)
                && notBlank(notifyUrl)
                && notBlank(apiV3Key)
                && notBlank(merchantSerialNo)
                && notBlank(privateKey);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
