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

    public void notifyDoorEvent(String sessionId,
                                String deviceId,
                                DoorState doorState,
                                String videoUri,
                                String uploadStatus,
                                String videoClipsJson,
                                String cameraFusionMode,
                                String gravityDeltasJson) {
        log.info("notify door event session={} device={} state={} video={} upload={} gravity={}",
                sessionId, deviceId, doorState, videoUri, uploadStatus,
                gravityDeltasJson != null ? "yes" : "no");

        DoorEventRequest request = new DoorEventRequest(
                sessionId, deviceId, doorState, System.currentTimeMillis(),
                videoUri, uploadStatus, videoClipsJson, cameraFusionMode, gravityDeltasJson);

        restClient.post()
                .uri("/internal/v1/sessions/door-event")
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionDto>>() {});
    }

    public void notifyDoorEvent(String sessionId, String deviceId, DoorState doorState, String videoUri) {
        notifyDoorEvent(sessionId, deviceId, doorState, videoUri, null, null, null, null);
    }

    public void notifyHeartbeat(String deviceId, String appVersion, String firmwareVersion) {
        notifyHeartbeat(deviceId, appVersion, firmwareVersion, null);
    }

    public void notifyHeartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC) {
        restClient.post()
                .uri("/internal/v1/devices/{deviceId}/heartbeat", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new HeartbeatBody(appVersion, firmwareVersion, currentTempC))
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

    record HeartbeatBody(String appVersion, String firmwareVersion, Integer currentTempC) {}
}
