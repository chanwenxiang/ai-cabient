package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.PayScoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AgreementChargeClient {

    private final PayScoreProperties properties;

    public AgreementChargeClient(PayScoreProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.hasChargeGateway();
    }

    public ChargeResponse charge(ChargeRequest request) {
        if (!isConfigured()) {
            throw new IllegalStateException("Agreement charge gateway is not configured");
        }
        RestClient client = RestClient.builder()
                .baseUrl(properties.chargeGatewayUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.chargeGatewayApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory())
                .build();
        ChargeResponse response = client.post()
                .uri("/charges")
                .body(request)
                .retrieve()
                .body(ChargeResponse.class);
        if (response == null || response.tradeNo() == null || response.tradeNo().isBlank()) {
            throw new IllegalStateException("Agreement charge gateway returned empty tradeNo");
        }
        if (response.status() != null && !"SUCCESS".equalsIgnoreCase(response.status())) {
            throw new IllegalStateException("Agreement charge failed: " + response.status());
        }
        return response;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return factory;
    }

    public record ChargeRequest(
            String channel,
            Long userId,
            String orderId,
            String agreementId,
            int amountCents,
            String description
    ) {}

    public record ChargeResponse(
            String tradeNo,
            String status
    ) {}
}
