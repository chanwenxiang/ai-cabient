package com.aicabinet.trade.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.trade.config.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.client.RestClient;

@Component
public class DeviceServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DeviceServiceClient.class);

    private final RestClient restClient;
    private final InternalApiProperties internalApiProperties;

    public DeviceServiceClient(@Value("${aicabinet.device-service.url:http://localhost:8081}") String baseUrl,
                               InternalApiProperties internalApiProperties) {
        this.restClient = InternalRestClientFactory.create(baseUrl);
        this.internalApiProperties = internalApiProperties;
    }

    @Retry(name = "deviceService")
    public void requestOpenDoor(String sessionId, String deviceId, Long userId, boolean operatorMode) {
        log.info("request open door: session={}, device={}, user={}, operator={}",
                sessionId, deviceId, userId, operatorMode);
        restClient.post()
                .uri("/internal/v1/devices/{deviceId}/open-door", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new OpenDoorRequest(sessionId, userId, operatorMode))
                .retrieve()
                .toBodilessEntity();
    }

    public void requestOpenDoorOperator(String sessionId, String deviceId, Long userId) {
        requestOpenDoor(sessionId, deviceId, userId, true);
    }

    public String requestSetTargetTemp(String deviceId, int targetTempC) {
        log.info("request set target temp: device={}, target={}", deviceId, targetTempC);
        return restClient.post()
                .uri("/internal/v1/devices/{deviceId}/set-target-temp", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new SetTargetTempRequest(targetTempC))
                .retrieve()
                .body(SetTargetTempResponse.class)
                .commandId();
    }

    record OpenDoorRequest(String sessionId, Long userId, boolean operatorMode) {}
    record SetTargetTempRequest(int targetTempC) {}
    record SetTargetTempResponse(String commandId) {}
}

