package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 微信支付 API v3 HTTP 客户端（JSON + WECHATPAY2 签名）。
 */
@Component
public class WeChatPayV3Client {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayV3Client.class);
    private static final String API_HOST = "https://api.mch.weixin.qq.com";

    private final WeChatPayProperties properties;
    private final WeChatPayV3Signer signer;
    private final WeChatPlatformCertificateStore certificateStore;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WeChatPayV3Client(WeChatPayProperties properties,
                             WeChatPayV3Signer signer,
                             WeChatPlatformCertificateStore certificateStore,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.certificateStore = certificateStore;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public JsonNode post(String path, Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            String auth = buildAuthorization("POST", path, json);
            String response = restClient.post()
                    .uri(API_HOST + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", auth)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("wechat v3 POST " + path + " failed", e);
        }
    }

    public JsonNode get(String path) {
        try {
            String auth = buildAuthorization("GET", path, "");
            String response = restClient.get()
                    .uri(API_HOST + path)
                    .header("Authorization", auth)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("wechat v3 GET " + path + " failed", e);
        }
    }

    public byte[] download(String url) {
        byte[] data = restClient.get()
                .uri(URI.create(url))
                .retrieve()
                .body(byte[].class);
        return data != null ? data : new byte[0];
    }

    public String createJsapiPrepayId(String openId, String outTradeNo, int totalCents, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", properties.appId());
        body.put("mchid", properties.mchId());
        body.put("description", description);
        body.put("out_trade_no", outTradeNo);
        body.put("notify_url", properties.notifyUrl());
        body.put("amount", Map.of("total", totalCents, "currency", "CNY"));
        body.put("payer", Map.of("openid", openId));

        log.info("wechat v3 jsapi order outTradeNo={}", outTradeNo);
        JsonNode resp = post("/v3/pay/transactions/jsapi", body);
        String prepayId = resp.path("prepay_id").asText(null);
        if (prepayId == null || prepayId.isBlank()) {
            throw new IllegalStateException("wechat v3 jsapi missing prepay_id: " + resp);
        }
        return prepayId;
    }

    public void closeOrder(String outTradeNo) {
        Map<String, Object> body = Map.of("mchid", properties.mchId());
        String path = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "/close";
        log.info("wechat v3 close order outTradeNo={}", outTradeNo);
        post(path, body);
    }

    public JsonNode queryByOutTradeNo(String outTradeNo) {
        String path = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "?mchid=" + properties.mchId();
        log.debug("wechat v3 query order outTradeNo={}", outTradeNo);
        return get(path);
    }

    public JsonNode createRefund(String outTradeNo, String outRefundNo, int refundCents, int totalCents, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", outTradeNo);
        body.put("out_refund_no", outRefundNo);
        body.put("reason", reason != null && !reason.isBlank() ? reason : "用户申请退款");
        body.put("amount", Map.of(
                "refund", refundCents,
                "total", totalCents,
                "currency", "CNY"
        ));
        log.info("wechat v3 refund outTradeNo={} outRefundNo={} refund={}", outTradeNo, outRefundNo, refundCents);
        return post("/v3/refund/domestic/refunds", body);
    }

    public boolean verifyNotifySignature(String timestamp, String nonce, String body,
                                         String signature, String serial) {
        Optional<String> certPem = certificateStore.resolveCertificatePem(serial);
        if (certPem.isEmpty()) {
            log.warn("wechat platform cert not available serial={}, skip notify signature verify", serial);
            return false;
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        return signer.verify(message, signature, certPem.get());
    }

    public String buildAuthorization(String method, String path, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = body != null ? body : "";
        String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + payload + "\n";
        String signature = signer.sign(message, properties.privateKey());
        return String.format(
                "WECHATPAY2-SHA256-RSA2048 mchid=\"%s\",nonce_str=\"%s\",timestamp=\"%s\",serial_no=\"%s\",signature=\"%s\"",
                properties.mchId(), nonce, timestamp, properties.merchantSerialNo(), signature);
    }

    public WeChatPayProperties properties() {
        return properties;
    }
}
