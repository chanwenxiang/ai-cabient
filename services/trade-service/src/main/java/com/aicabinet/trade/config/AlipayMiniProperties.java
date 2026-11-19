package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付宝**小程序**应用配置（与网页应用分开）。
 *
 * <p>为什么必须分开：支付宝的 app_id 绑定「应用类型」——网页应用只能调
 * {@code alipay.trade.wap.pay}；小程序内支付（{@code alipay.trade.create} +
 * 客户端 {@code my.tradePay}）必须用**小程序应用**的 app_id。两者是同一开放平台
 * 账号下的两个应用：应用私钥可以共用一套，但 app_id 不能互换（换错会直接报
 * 「应用未开通小程序支付」）。
 *
 * <p>未配置时：小程序端充值/结算 fail-closed，**不**退回 wap——小程序里提交
 * HTML 表单会被支付宝客户端拦截，用户只会看到白屏，比明确报错更难排查。
 */
@ConfigurationProperties(prefix = "aicabinet.alipay.mp")
public record AlipayMiniProperties(boolean enabled, String appId) {

    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank();
    }
}
