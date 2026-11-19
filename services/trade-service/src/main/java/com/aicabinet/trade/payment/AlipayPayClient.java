package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayMiniProperties;
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
    private final AlipayMiniProperties miniProperties;
    private final AlipayOpenApiClient openApiClient;

    public AlipayPayClient(AlipayProperties properties,
                           AlipayMiniProperties miniProperties,
                           AlipayOpenApiClient openApiClient) {
        this.properties = properties;
        this.miniProperties = miniProperties;
        this.openApiClient = openApiClient;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 小程序内支付是否可用（需要**小程序应用**的 app_id，见 {@link AlipayMiniProperties}）。 */
    public boolean isMiniConfigured() {
        return properties.isConfigured() && miniProperties.isConfigured();
    }

    /**
     * 支付宝小程序支付：{@code alipay.trade.create}（JSAPI）→ trade_no，客户端用
     * {@code my.tradePay({tradeNO})} 拉起收银台。
     *
     * <p>🔴 两条与 {@link #createWapPay} 不可互换的硬约束：
     * <ol>
     *   <li>小程序里**不能**提交 HTML 表单（支付宝客户端会拦截），只能拿 trade_no 由客户端调起；</li>
     *   <li>{@code trade.create} **必须带 buyer_id**（= 支付宝 user_id，登录时由
     *       {@code alipay.system.oauth.token} 取得）。因此只有「已在支付宝内授权登录」的
     *       用户能走这条 —— 拿不到 buyerId 时必须 fail-closed，而不是退回 wap。</li>
     * </ol>
     *
     * @param buyerId 支付宝 user_id（user_info.alipay_user_id）
     */
    public AlipayPrepayResult createMiniPay(String outTradeNo, int amountCents, String subject, String buyerId) {
        if (!isMiniConfigured()) {
            throw new IllegalStateException("alipay mini program not configured");
        }
        if (buyerId == null || buyerId.isBlank()) {
            throw new IllegalStateException("alipay buyer_id missing");
        }
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put(OUT_TRADE_NO, outTradeNo);
        biz.put("total_amount", formatAmount(amountCents));
        biz.put("subject", subject == null || subject.isBlank() ? "AI开门柜充值" : subject);
        biz.put("buyer_id", buyerId.trim());
        // JSAPI_PAY = 小程序支付；不带 product_code 时支付宝会按 wap 处理并报「交易类型不符」
        biz.put("product_code", "JSAPI_PAY");
        JsonNode resp = openApiClient.executeAs(miniProperties.appId(), "alipay.trade.create", biz);
        String tradeNo = resp.path("trade_no").asText(null);
        if (tradeNo == null || tradeNo.isBlank()) {
            throw new IllegalStateException("alipay trade.create missing trade_no: " + resp);
        }
        return new AlipayPrepayResult(tradeNo, null, null);
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
