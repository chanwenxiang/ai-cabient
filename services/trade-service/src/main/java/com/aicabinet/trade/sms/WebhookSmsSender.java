package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

@Component
public class WebhookSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSmsSender.class);

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public WebhookSmsSender(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(String phoneNumber, String code) {
        dispatch(phoneNumber, Map.of("code", code), "SMS code");
    }

    /** 通知类短信：webhook 载荷为 phoneNumber + message。 */
    public void sendMessage(String phoneNumber, String message) {
        dispatch(phoneNumber, Map.of("message", message), "SMS message");
    }

    private void dispatch(String phoneNumber, Map<String, String> payload, String kind) {
        String url = authProperties.sms().webhookUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("SMS webhook URL not configured");
        }
        try {
            Map<String, String> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("phoneNumber", phoneNumber);
            bodyMap.putAll(payload);
            byte[] body = objectMapper.writeValueAsBytes(bodyMap);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int status = conn.getResponseCode();
            if (status >= 400) {
                throw new IllegalStateException("SMS webhook returned HTTP " + status);
            }
            String masked = maskPhone(phoneNumber);
            log.info("{} dispatched via webhook phone={}", kind, masked);
        } catch (Exception e) {
            throw new IllegalStateException("SMS webhook dispatch failed", e);
        }
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
