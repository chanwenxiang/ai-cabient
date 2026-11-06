package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 阿里云短信验证码（SendSms）。未配置 accessKey 时不启用，由 {@link RoutingSmsSender} 回退 webhook。
 */
@Component
public class AliyunSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsSender.class);
    private static final String ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public AliyunSmsSender(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        AuthProperties.SmsProperties sms = authProperties.sms();
        return sms.aliyunAccessKeyId() != null && !sms.aliyunAccessKeyId().isBlank()
                && sms.aliyunAccessKeySecret() != null && !sms.aliyunAccessKeySecret().isBlank()
                && sms.aliyunSignName() != null && !sms.aliyunSignName().isBlank()
                && sms.aliyunTemplateCode() != null && !sms.aliyunTemplateCode().isBlank();
    }

    @Override
    public void send(String phoneNumber, String code) {
        if (!isConfigured()) {
            throw new IllegalStateException("Aliyun SMS not configured");
        }
        doSend(phoneNumber, sms().aliyunTemplateCode(), Map.of("code", code));
    }

    /**
     * 通知类短信（模板渲染）。阿里云通知短信强制要求模板+变量（与验证码同一 SendSms 接口），
     * 模板 CODE 由调用方传入（运营台可配）。变量值会原样下发——电话号码等需调用方自行清洗
     * （阿里云「电话号码」属性只认 5~11 位）。
     */
    public void sendMessage(String phoneNumber, String templateCode, Map<String, String> templateVars) {
        if (!isConfigured()) {
            throw new IllegalStateException("Aliyun SMS not configured");
        }
        doSend(phoneNumber, templateCode, templateVars);
    }

    private AuthProperties.SmsProperties sms() {
        return authProperties.sms();
    }

    private void doSend(String phoneNumber, String templateCode, Map<String, String> templateVars) {
        AuthProperties.SmsProperties sms = authProperties.sms();
        try {
            String templateParam = objectMapper.writeValueAsString(templateVars);
            TreeMap<String, String> params = baseRpcParams(sms);
            params.put("Action", "SendSms");
            params.put("PhoneNumbers", phoneNumber);
            params.put("SignName", sms.aliyunSignName());
            params.put("TemplateCode", templateCode);
            params.put("TemplateParam", templateParam);

            String body = rpcBody(params, sms.aliyunAccessKeySecret());
            HttpResult res = postForm(ENDPOINT, body);
            verifyAliyunAccepted(res.status(), res.body());
            String masked = maskPhone(phoneNumber);
            log.info("SMS code dispatched via Aliyun phone={}", masked);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Aliyun SMS dispatch failed", e);
        }
    }

    /**
     * 一条阿里云短信发送回执。🔴 字段集就是 {@code QuerySendDetails} 实际返回的那些——
     * **里面没有 BizId**，所以回执对不回我方某一行具体日志（2026-11-06 探针实证：
     * 同号同日多次发送分不开）⇒ 只能按 (号码, 日) 做聚合口径，别拿它做逐行回填。
     *
     * @param sendStatus 1=发送中 2=失败 3=送达（阿里云语义，不是我方状态）
     * @param errCode    {@code DELIVERED} / {@code CONTENT_KEYWORD}（正文被运营商拒）/ null=还没回执
     */
    public record SmsReceipt(String phoneNum, String sendDate, String receiveDate,
                             int sendStatus, String errCode, String templateCode) {}

    /** 一页回执 + 该 (号码,日) 的总条数（分页用）。 */
    record ReceiptPage(List<SmsReceipt> rows, int total) {}

    /**
     * 拉某号码某天的发送回执（只读，不改任何状态）。
     *
     * <p>参数名是 {@code PhoneNumber}（**单数**），与发送侧的 {@code PhoneNumbers} 不同——
     * 写错会被 {@code MissingPhoneNumber} 拒。日期格式 {@code yyyyMMdd}，且阿里云只保留最近几天
     * ⇒ 事后补扫来不及，必须近实时轮询。</p>
     *
     * @return 回执行；未配置凭据时返回空列表（调用方应先判 {@link #isConfigured()}）
     * @throws IllegalStateException HTTP 非 2xx 或 {@code Code≠OK} ⇒ 抛；「查不到」与「查询失败」必须分开
     */
    public List<SmsReceipt> querySendDetails(String phoneNumber, String sendDateYmd) {
        if (!isConfigured()) {
            return List.of();
        }
        List<SmsReceipt> out = new ArrayList<>();
        try {
            for (int page = 1; page <= 10; page++) {
                AuthProperties.SmsProperties sms = authProperties.sms();
                TreeMap<String, String> params = baseRpcParams(sms);
                params.put("Action", "QuerySendDetails");
                params.put("PhoneNumber", phoneNumber);
                params.put("SendDate", sendDateYmd);
                params.put("PageSize", "50");
                params.put("CurrentPage", String.valueOf(page));
                HttpResult res = postForm(ENDPOINT, rpcBody(params, sms.aliyunAccessKeySecret()));
                ReceiptPage rp = parseSendDetails(res.status(), res.body(), objectMapper);
                out.addAll(rp.rows());
                if (out.size() >= rp.total() || rp.rows().isEmpty()) {
                    break;
                }
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Aliyun SMS receipt query failed", e);
        }
    }

    /**
     * 解析回执响应体（纯函数，单测直喂真实响应字符串即可，不出网）。
     * HTTP ≥400 或 {@code Code≠OK} 一律抛——把「凭据过期/接口不通」读成「今天零失败」是不可接受的假绿。
     */
    static ReceiptPage parseSendDetails(int status, String responseBody, ObjectMapper mapper) {
        if (status >= 400) {
            log.warn("Aliyun receipt query HTTP {} body={}", status, truncate(responseBody));
            throw new IllegalStateException("Aliyun receipt query HTTP " + status);
        }
        try {
            var root = mapper.readTree(responseBody);
            String code = root.path("Code").asText("");
            if (!"OK".equals(code)) {
                throw new IllegalStateException("Aliyun receipt query Code="
                        + (code.isBlank() ? "-" : code) + " msg=" + root.path("Message").asText(""));
            }
            int total = root.path("TotalCount").asInt(0);
            List<SmsReceipt> rows = new ArrayList<>();
            for (var n : root.path("SmsSendDetailDTOs").path("SmsSendDetailDTO")) {
                rows.add(new SmsReceipt(
                        n.path("PhoneNum").asText(null),
                        n.path("SendDate").asText(null),
                        n.path("ReceiveDate").asText(null),
                        n.path("SendStatus").asInt(0),
                        n.hasNonNull("ErrCode") ? n.path("ErrCode").asText("") : null,
                        n.path("TemplateCode").asText(null)));
            }
            return new ReceiptPage(List.copyOf(rows), total);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Aliyun receipt query unparsable body", e);
        }
    }

    /** RPC 公共参数（版本/签名法/时间戳…）——发送与回执查询共用，避免两份实现各自漂移。 */
    private static TreeMap<String, String> baseRpcParams(AuthProperties.SmsProperties sms) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", sms.aliyunAccessKeyId());
        params.put("Format", "JSON");
        params.put("RegionId", blankOr(sms.aliyunRegionId(), "cn-hangzhou"));
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", ISO.format(Instant.now()));
        params.put("Version", "2017-05-25");
        return params;
    }

    /** 规范串 → HMAC-SHA1 签名 → 表单体（{@code doSend} 与回执查询共用同一实现）。 */
    static String rpcBody(SortedMap<String, String> params, String accessKeySecret) throws Exception {
        String canonical = params.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        String stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonical);
        String signature = sign(stringToSign, accessKeySecret + "&");
        return "Signature=" + percentEncode(signature) + "&" + canonical;
    }

    record HttpResult(int status, String body) {}

    /**
     * POST 表单体并读回响应。H14：4xx/5xx 也读响应体（阿里云错误详情在 body），
     * 只看状态码会把流控/关键词拒发当成功。
     */
    static HttpResult postForm(String endpoint, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int status = conn.getResponseCode();
        return new HttpResult(status, readResponseBody(conn, status));
    }

    /**
     * H14：统一判定发送结果。阿里云在业务被拒（如 {@code isv.BUSINESS_LIMIT_CONTROL} 流控）时
     * 仍返回 HTTP 200，只看状态码会把失败当成功；必须解析响应体 {@code Code}，仅 {@code OK} 算成功。
     */
    void verifyAliyunAccepted(int status, String responseBody) {
        if (status >= 400) {
            log.warn("Aliyun SMS HTTP {} body={}", status, truncate(responseBody));
            throw new IllegalStateException("Aliyun SMS HTTP " + status);
        }
        String code;
        String message;
        try {
            JsonNode node = objectMapper.readTree(responseBody == null ? "" : responseBody);
            code = node.path("Code").asText("");
            message = node.path("Message").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Aliyun SMS unexpected response body: " + truncate(responseBody));
        }
        if (!"OK".equals(code)) {
            log.warn("Aliyun SMS rejected code={} message={}", code, message);
            throw new IllegalStateException("Aliyun SMS business error code=" + code + " message=" + message);
        }
    }

    private static String readResponseBody(HttpURLConnection conn, int status) throws IOException {
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    private static String blankOr(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static String sign(String stringToSign, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        // 阿里云 OpenAPI RPC 签名算法规定 HmacSHA1，不可换用更强摘要
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    }

    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
