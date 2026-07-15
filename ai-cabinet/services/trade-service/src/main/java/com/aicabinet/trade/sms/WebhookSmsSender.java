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
        String url = authProperties.sms().webhookUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("SMS webhook URL not configured");
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of("phoneNumber", phoneNumber, "code", code));
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
            log.info("SMS dispatched via webhook phone={}", maskPhone(phoneNumber));
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
