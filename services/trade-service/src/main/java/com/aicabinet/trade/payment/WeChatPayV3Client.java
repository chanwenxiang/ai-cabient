package com.aicabinet.trade.payment;

import com.aicabinet.common.http.OutboundHttp;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** 仅用于对账单文件下载（读超时更长）。 */
    private final RestClient downloadRestClient;

    @Autowired
    public WeChatPayV3Client(WeChatPayProperties properties,
                             WeChatPayV3Signer signer,
                             WeChatPlatformCertificateStore certificateStore,
                             ObjectMapper objectMapper) {
        this(properties, signer, certificateStore, objectMapper, OutboundHttp.bulkDownload());
    }

    WeChatPayV3Client(WeChatPayProperties properties,
                      WeChatPayV3Signer signer,
                      WeChatPlatformCertificateStore certificateStore,
                      ObjectMapper objectMapper,
                      RestClient.Builder downloadRestClientBuilder) {
        this.properties = properties;
        this.signer = signer;
        this.certificateStore = certificateStore;
        this.objectMapper = objectMapper;
        this.restClient = OutboundHttp.publicApi().build();
        this.downloadRestClient = downloadRestClientBuilder.build();
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

    /**
     * MA-407：带 HTTP 状态码的 POST——商家转账要靠错误码区分「确定失败」与「结果不确定」，
     * 现有 {@link #post} 把所有异常吞成 IllegalStateException 拿不到状态码，故新增本方法。
     *
     * <p>不改 {@link #post} 行为（支付/退款调用方依赖其异常语义）。4xx/5xx 走 exchange 不抛，
     * 原样返回 status + 错误 body（微信 v3 错误体形如 {"code":"NOT_ENOUGH","message":"..."}）。
     */
    public HttpResult postWithStatus(String path, Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            String auth = buildAuthorization("POST", path, json);
            var response = restClient.post()
                    .uri(API_HOST + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", auth)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .body(json)
                    .exchange((req, res) -> {
                        String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        JsonNode parsed = text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
                        return new HttpResult(res.getStatusCode().value(), parsed);
                    });
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("wechat v3 POST " + path + " failed", e);
        }
    }

    public record HttpResult(int status, JsonNode body) {
        /** 微信 v3 错误码（2xx 时通常为空）。 */
        public String code() {
            return body.path("code").asText(null);
        }

        public String message() {
            return body.path("message").asText(null);
        }

        public boolean is2xx() {
            return status >= 200 && status < 300;
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

    /**
     * MA-407：带 HTTP 状态码的 GET（转账查单用）。
     *
     * <p>查单必须能区分「单不存在（404 NOT_FOUND）」与「权限/网络问题」：
     * {@link #get} 把两者都吞成 IllegalStateException，查询闭环就没法判「这笔到底有没有建单」。
     */
    public HttpResult getWithStatus(String path) {
        try {
            String auth = buildAuthorization("GET", path, "");
            return restClient.get()
                    .uri(API_HOST + path)
                    .header("Authorization", auth)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .exchange((req, res) -> {
                        String text = new String(res.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        JsonNode parsed = text.isBlank()
                                ? objectMapper.createObjectNode() : objectMapper.readTree(text);
                        return new HttpResult(res.getStatusCode().value(), parsed);
                    });
        } catch (Exception e) {
            throw new IllegalStateException("wechat v3 GET " + path + " failed", e);
        }
    }

    public byte[] download(String url, String hashType, String hashValue) {
        URI uri = URI.create(url);
        String path = uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String auth = buildAuthorization("GET", path, "");
        byte[] data = downloadRestClient.get()
                .uri(uri)
                .header("Authorization", auth)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(byte[].class);
        if (data == null) {
            throw new IllegalStateException("wechat bill download returned empty body");
        }
        verifyHash(data, hashType, hashValue);
        return data;
    }

    private static void verifyHash(byte[] data, String hashType, String expected) {
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("wechat bill hash_value missing");
        }
        String algorithm = switch (hashType == null ? "" : hashType.trim().toUpperCase()) {
            case "SHA1" -> "SHA-1";
            case "SHA256" -> "SHA-256";
            default -> throw new IllegalStateException("unsupported wechat bill hash_type: " + hashType);
        };
        try {
            byte[] digest = java.security.MessageDigest.getInstance(algorithm).digest(data);
            String actual = java.util.HexFormat.of().formatHex(digest);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("wechat bill hash mismatch");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("wechat bill hash algorithm unavailable: " + algorithm, e);
        }
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

    /**
     * H42(b): 查询单笔退款（GET /v3/refund/domestic/refunds/{out_refund_no}）。
     * 响应含 status: PROCESSING / SUCCESS / ABNORMAL，供对账调度推进退款状态。
     * 退款查询接口不接受 mchid 查询参数，商户身份由 Authorization 签名头携带。
     */
    public JsonNode queryRefund(String outTradeNo, String outRefundNo) {
        String path = "/v3/refund/domestic/refunds/" + outRefundNo;
        log.debug("wechat v3 query refund outTradeNo={} outRefundNo={}", outTradeNo, outRefundNo);
        return get(path);
    }

    public boolean verifyNotifySignature(String timestamp, String nonce, String body,
                                         String signature, String serial) {
        Optional<String> certPem = certificateStore.resolveCertificatePem(serial);
        if (certPem.isEmpty()) {
            log.warn("wechat verification key not available keyId={}, skip notify signature verify", serial);
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
