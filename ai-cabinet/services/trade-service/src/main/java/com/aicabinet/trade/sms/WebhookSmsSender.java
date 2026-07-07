package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WebhookSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSmsSender.class);

    private final AuthProperties authProperties;
    private final RestClient restClient;

    public WebhookSmsSender(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.restClient = RestClient.create();
    }

    @Override
    public void send(String phoneNumber, String code) {
        String url = authProperties.sms().webhookUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("SMS webhook URL not configured");
        }
        restClient.post()
                .uri(url)
                .body(Map.of("phoneNumber", phoneNumber, "code", code))
                .retrieve()
                .toBodilessEntity();
        log.info("SMS dispatched via webhook phone={}", maskPhone(phoneNumber));
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
