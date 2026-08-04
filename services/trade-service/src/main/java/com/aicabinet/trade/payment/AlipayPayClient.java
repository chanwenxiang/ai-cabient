package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AlipayPayClient {

    private final AlipayProperties properties;
    private final AlipayOpenApiClient openApiClient;

    public AlipayPayClient(AlipayProperties properties, AlipayOpenApiClient openApiClient) {
        this.properties = properties;
        this.openApiClient = openApiClient;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public AlipayPrepayResult createWapPay(String outTradeNo, int amountCents, String subject) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", outTradeNo);
        biz.put("total_amount", formatAmount(amountCents));
        biz.put("subject", subject);
        biz.put("product_code", "QUICK_WAP_WAY");
        String quitUrl = properties.returnUrl();
        if (quitUrl != null && !quitUrl.isBlank()) {
            biz.put("quit_url", quitUrl);
        }

        String payFormHtml = openApiClient.buildPagePayFormHtml("alipay.trade.wap.pay", biz, quitUrl);
        return new AlipayPrepayResult(null, null, payFormHtml);
    }

    public JsonNode queryByOutTradeNo(String outTradeNo) {
        return openApiClient.execute("alipay.trade.query", Map.of("out_trade_no", outTradeNo));
    }

    public void closeOrder(String outTradeNo) {
        openApiClient.execute("alipay.trade.close", Map.of("out_trade_no", outTradeNo));
    }

    public void refund(String outTradeNo, String outRefundNo, int refundCents, String reason) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", outTradeNo);
        biz.put("out_request_no", outRefundNo);
        biz.put("refund_amount", formatAmount(refundCents));
        biz.put("refund_reason", reason != null ? reason : "用户退款");
        openApiClient.execute("alipay.trade.refund", biz);
    }

    private static String formatAmount(int amountCents) {
        return BigDecimal.valueOf(amountCents, 2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record AlipayPrepayResult(String tradeNo, String payUrl, String payFormHtml) {}
}
