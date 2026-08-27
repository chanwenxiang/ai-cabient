package com.aicabinet.trade.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 微信支付 API v3 客户端门面（JSAPI 下单）。
 */
@Component
public class WeChatPayClient {

    private final WeChatPayV3Client v3Client;

    public WeChatPayClient(WeChatPayV3Client v3Client) {
        this.v3Client = v3Client;
    }

    public String unifiedOrderJsapi(String openId, String outTradeNo, int totalFeeCents, String body) {
        return v3Client.createJsapiPrepayId(openId, outTradeNo, totalFeeCents, body);
    }

    public void closeOrder(String outTradeNo) {
        v3Client.closeOrder(outTradeNo);
    }

    public JsonNode queryByOutTradeNo(String outTradeNo) {
        return v3Client.queryByOutTradeNo(outTradeNo);
    }

    public JsonNode createRefund(String outTradeNo, String outRefundNo, int refundCents, int totalCents, String reason) {
        return v3Client.createRefund(outTradeNo, outRefundNo, refundCents, totalCents, reason);
    }

    public WeChatPayV3Client v3() {
        return v3Client;
    }
}
