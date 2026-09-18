package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.PayScoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class AgreementChargeClient {

    private final PayScoreProperties properties;

    public AgreementChargeClient(PayScoreProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.hasChargeGateway();
    }

    /**
     * H50/M15: 请求携带稳定 {@code Idempotency-Key}（调用方传入，如 orderId 或 orderId+"-ADJ"），
     * 网关超时/未知异常后可凭同键重发或查单确认，避免重复扣款。
     */
    public ChargeResponse charge(ChargeRequest request) {
        if (!isConfigured()) {
            throw new AgreementNotConfiguredException("Agreement charge gateway is not configured");
        }
        RestClient client = client();
        ChargeResponse response = client.post()
                .uri("/charges")
                .header("Idempotency-Key",
                        request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                                ? request.orderId()
                                : request.idempotencyKey())
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

    /**
     * H50/M15: 按商户侧扣款单号（charge 时的 orderId / Idempotency-Key）查单确认。
     * 网关无该单返回 empty；调用方在超时/未知异常时据此决定「确认结果」或「同键重发」。
     */
    public Optional<ChargeResponse> queryCharge(String chargeIdOrOutNo) {
        if (!isConfigured()) {
            throw new AgreementNotConfiguredException("Agreement charge gateway is not configured");
        }
        if (chargeIdOrOutNo == null || chargeIdOrOutNo.isBlank()) {
            return Optional.empty();
        }
        RestClient client = client();
        ChargeResponse response = client.get()
                .uri("/charges/{id}", chargeIdOrOutNo.trim())
                .retrieve()
                .body(ChargeResponse.class);
        return Optional.ofNullable(response);
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(properties.chargeGatewayUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.chargeGatewayApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory())
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return factory;
    }

    /** H41(b): 明确的「网关未配置」类异常，允许上层做确定性 fallback；超时/未知异常不得盲切。 */
    public static class AgreementNotConfiguredException extends IllegalStateException {
        public AgreementNotConfiguredException(String message) {
            super(message);
        }
    }

    public record ChargeRequest(
            String channel,
            Long userId,
            String orderId,
            String agreementId,
            int amountCents,
            String description,
            String idempotencyKey
    ) {}

    public record ChargeResponse(
            String tradeNo,
            String status
    ) {}
}
