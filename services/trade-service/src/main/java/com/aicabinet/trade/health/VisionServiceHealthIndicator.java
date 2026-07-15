package com.aicabinet.trade.health;

import com.aicabinet.trade.config.VisionApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VisionServiceHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(VisionServiceHealthIndicator.class);

    private final RestClient restClient;
    private final VisionApiProperties visionApiProperties;

    public VisionServiceHealthIndicator(VisionApiProperties visionApiProperties) {
        this.visionApiProperties = visionApiProperties;
        this.restClient = RestClient.builder()
                .baseUrl(System.getenv().getOrDefault("AICABINET_VISION_SERVICE_URL", "http://localhost:8082"))
                .build();
    }

    @Override
    public Health health() {
        try {
            var response = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(VisionHealthResponse.class);
            if (response != null && "ok".equalsIgnoreCase(response.status())) {
                Health.Builder up = Health.up();
                if (response.modelVersion() != null) {
                    up.withDetail("modelVersion", response.modelVersion());
                }
                up.withDetail("recognizerAvailable", response.recognizerAvailable());
                up.withDetail("mockEnabled", response.mockEnabled());
                return up.build();
            }
            return Health.down().withDetail("reason", "vision response not ok").build();
        } catch (Exception e) {
            log.warn("vision health check failed: {}", e.getMessage());
            return Health.down().withDetail("error", String.valueOf(e.getMessage())).build();
        }
    }

    private record VisionHealthResponse(
            String status,
            @com.fasterxml.jackson.annotation.JsonProperty("model_version") String modelVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("recognizer_available") boolean recognizerAvailable,
            @com.fasterxml.jackson.annotation.JsonProperty("mock_enabled") boolean mockEnabled) {}
}
