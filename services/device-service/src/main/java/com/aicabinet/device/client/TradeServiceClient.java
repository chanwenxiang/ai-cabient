package com.aicabinet.device.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.VideoAttachRequest;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.security.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TradeServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TradeServiceClient.class);

    private final RestClient restClient;
    private final InternalApiProperties internalApiProperties;

    public TradeServiceClient(@Value("${aicabinet.trade-service.url:http://localhost:8080}") String baseUrl,
                              InternalApiProperties internalApiProperties) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalApiProperties = internalApiProperties;
    }

    public void notifyDoorEvent(DoorEventRequest request) {
        log.info("notify door event session={} device={} state={} video={} upload={} gravity={}",
                request.sessionId(), request.deviceId(), request.doorState(), request.videoUri(),
                request.uploadStatus(), request.gravityDeltasJson() != null ? "yes" : "no");

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                restClient.post()
                        .uri("/internal/v1/sessions/door-event")
                        .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                        .body(request)
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiResponse<SessionDto>>() {});
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("notify door event failed attempt={}/3 session={}: {}",
                        attempt, request.sessionId(), e.toString());
                if (attempt < 3) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("notify door event failed");
    }

    public void notifyDoorEvent(String sessionId, String deviceId, DoorState doorState, String videoUri) {
        notifyDoorEvent(new DoorEventRequest(
                sessionId, deviceId, doorState, System.currentTimeMillis(),
                videoUri, null, null, null, null));
    }

    public void notifyHeartbeat(String deviceId, String appVersion, String firmwareVersion) {
        notifyHeartbeat(deviceId, appVersion, firmwareVersion, null);
    }

    public void notifyHeartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC) {
        notifyHeartbeat(deviceId, appVersion, firmwareVersion, currentTempC, null, null, null);
    }

    public void notifyHeartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC,
                                Double humidityPct, Double voltageV, Double powerW) {
        restClient.post()
                .uri("/internal/v1/devices/{deviceId}/heartbeat", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new HeartbeatBody(appVersion, firmwareVersion, currentTempC,
                        humidityPct, voltageV, powerW))
                .retrieve()
                .toBodilessEntity();
    }

    public void notifyHeartbeat(String deviceId) {
        notifyHeartbeat(deviceId, null, null);
    }

    public void attachVideo(String sessionId,
                            String deviceId,
                            String videoUri,
                            String uploadStatus,
                            String videoClipsJson,
                            String cameraFusionMode) {
        restClient.post()
                .uri("/internal/v1/sessions/video")
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new VideoAttachRequest(
                        sessionId, deviceId, videoUri, uploadStatus, videoClipsJson, cameraFusionMode))
                .retrieve()
                .toBodilessEntity();
    }

    public void attachVideo(String sessionId, String deviceId, String videoUri) {
        attachVideo(sessionId, deviceId, videoUri, "UPLOADED", null, null);
    }

    record HeartbeatBody(String appVersion, String firmwareVersion, Integer currentTempC,
                         Double humidityPct, Double voltageV, Double powerW) {}
}
