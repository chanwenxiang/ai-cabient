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

import java.util.Map;

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

    /** 柜机是否已在 trade 注册（B-22）。trade 不可达时返回 false，避免向未知设备发令。 */
    public boolean deviceExists(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        try {
            ApiResponse<Map<String, Object>> body = restClient.get()
                    .uri("/internal/v1/devices/{deviceId}/exists", deviceId)
                    .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {});
            if (body == null || body.data() == null) {
                return false;
            }
            Object exists = body.data().get("exists");
            return Boolean.TRUE.equals(exists) || "true".equalsIgnoreCase(String.valueOf(exists));
        } catch (RuntimeException e) {
            log.warn("device exists check failed deviceId={}: {}", deviceId, e.toString());
            return false;
        }
    }

    /**
     * H54：OPEN_DOOR 命令 ACK 超时，通知 trade 立即把会话置为失败（不等 trade 侧 90s 兜底）。
     * 抛出异常由调用方（DeviceCommandTracker）捕获并仅记 warn。
     */
    public void openDoorFailed(String sessionId, String reason) {
        restClient.post()
                .uri("/internal/v1/sessions/{sessionId}/open-failed", sessionId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new OpenDoorFailedBody(reason))
                .retrieve()
                .toBodilessEntity();
    }

    /** H62a：转发柜机 edge 告警事件到 trade 运营告警通道（薄端点转发 OpsAlertDispatcher）。 */
    public void notifyOpsAlert(String alertType, String message, String deviceId) {
        restClient.post()
                .uri("/internal/v1/ops-alerts")
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new EdgeAlertBody(alertType, message, deviceId))
                .retrieve()
                .toBodilessEntity();
    }

    record HeartbeatBody(String appVersion, String firmwareVersion, Integer currentTempC,
                         Double humidityPct, Double voltageV, Double powerW) {}

    record OpenDoorFailedBody(String reason) {}

    record EdgeAlertBody(String alertType, String message, String deviceId) {}
}
