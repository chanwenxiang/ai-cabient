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
    private static final String OUT_TRADE_NO = "out_trade_no";


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
        biz.put(OUT_TRADE_NO, outTradeNo);
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

    /**
     * 协议签约页面表单（支付宝内 H5 自动提交）。
     * externalAgreementNo 为我方单号，回调里用其绑定用户。
     */
    public String createAgreementSignForm(String externalAgreementNo) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("personal_product_code", properties.resolvedAgreementProductCode());
        biz.put("sign_scene", properties.resolvedAgreementSignScene());
        biz.put("external_agreement_no", externalAgreementNo);
        Map<String, Object> accessParams = new LinkedHashMap<>();
        accessParams.put("channel", "ALIPAYAPP");
        biz.put("access_params", accessParams);
        String returnUrl = properties.returnUrl();
        String notifyUrl = properties.resolvedAgreementNotifyUrl();
        return openApiClient.buildPagePayFormHtml(
                "alipay.user.agreement.page.sign", biz, returnUrl, notifyUrl);
    }

    /**
     * 协议代扣：alipay.trade.pay + agreement_params.agreement_no。
     * @return 支付宝 trade_no
     */
    public String payWithAgreement(String outTradeNo, String agreementNo, int amountCents, String subject) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put(OUT_TRADE_NO, outTradeNo);
        biz.put("total_amount", formatAmount(amountCents));
        biz.put("subject", subject == null || subject.isBlank() ? "AI开门柜购物" : subject);
        biz.put("product_code", "GENERAL_WITHHOLDING");
        Map<String, Object> agreementParams = new LinkedHashMap<>();
        agreementParams.put("agreement_no", agreementNo);
        biz.put("agreement_params", agreementParams);
        JsonNode resp = openApiClient.execute("alipay.trade.pay", biz);
        String tradeNo = resp.path("trade_no").asText(null);
        if (tradeNo == null || tradeNo.isBlank()) {
            throw new IllegalStateException("alipay agreement pay missing trade_no: " + resp);
        }
        return tradeNo;
    }

    public JsonNode queryByOutTradeNo(String outTradeNo) {
        return openApiClient.execute("alipay.trade.query", Map.of(OUT_TRADE_NO, outTradeNo));
    }

    public void closeOrder(String outTradeNo) {
        openApiClient.execute("alipay.trade.close", Map.of(OUT_TRADE_NO, outTradeNo));
    }

    public void refund(String outTradeNo, String outRefundNo, int refundCents, String reason) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put(OUT_TRADE_NO, outTradeNo);
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
