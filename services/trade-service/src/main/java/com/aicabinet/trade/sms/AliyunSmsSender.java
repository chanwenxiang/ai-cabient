package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.util.Base64;
import java.util.Map;
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
        AuthProperties.SmsProperties sms = authProperties.sms();
        try {
            String templateParam = objectMapper.writeValueAsString(Map.of("code", code));
            TreeMap<String, String> params = new TreeMap<>();
            params.put("AccessKeyId", sms.aliyunAccessKeyId());
            params.put("Action", "SendSms");
            params.put("Format", "JSON");
            params.put("PhoneNumbers", phoneNumber);
            params.put("RegionId", blankOr(sms.aliyunRegionId(), "cn-hangzhou"));
            params.put("SignName", sms.aliyunSignName());
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("SignatureVersion", "1.0");
            params.put("TemplateCode", sms.aliyunTemplateCode());
            params.put("TemplateParam", templateParam);
            params.put("Timestamp", ISO.format(Instant.now()));
            params.put("Version", "2017-05-25");

            String canonical = params.entrySet().stream()
                    .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
                    .collect(Collectors.joining("&"));
            String stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonical);
            String signature = sign(stringToSign, sms.aliyunAccessKeySecret() + "&");
            String body = "Signature=" + percentEncode(signature) + "&" + canonical;

            HttpURLConnection conn = (HttpURLConnection) URI.create(ENDPOINT).toURL().openConnection();
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
            if (status >= 400) {
                throw new IllegalStateException("Aliyun SMS HTTP " + status);
            }
            String masked = maskPhone(phoneNumber);
            log.info("SMS code dispatched via Aliyun phone={}", masked);
        } catch (Exception e) {
            throw new IllegalStateException("Aliyun SMS dispatch failed", e);
        }
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
