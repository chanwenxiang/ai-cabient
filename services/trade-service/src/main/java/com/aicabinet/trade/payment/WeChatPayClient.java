package com.aicabinet.trade.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 微信支付 API v3 客户端门面（JSAPI 下单）。
 */
@Component
public class WeChatPayClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayClient.class);

    private final WeChatPayV3Client v3Client;

    public WeChatPayClient(WeChatPayV3Client v3Client) {
        this.v3Client = v3Client;
    }

    public String unifiedOrderJsapi(String openId, String outTradeNo, int totalFeeCents, String body, String clientIp) {
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
