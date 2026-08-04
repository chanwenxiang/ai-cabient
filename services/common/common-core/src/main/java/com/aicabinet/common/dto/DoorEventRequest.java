package com.aicabinet.common.dto;

import com.aicabinet.common.enums.DoorState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DoorEventRequest(
        @NotBlank String sessionId,
        @NotBlank String deviceId,
        @NotNull DoorState doorState,
        Long timestamp,
        String videoUri,
        String uploadStatus,
        String videoClipsJson,
        String cameraFusionMode,
        String gravityDeltasJson
) {
    public DoorEventRequest(String sessionId, String deviceId, DoorState doorState, Long timestamp) {
        this(sessionId, deviceId, doorState, timestamp, null, null, null, null, null);
    }

    public DoorEventRequest(String sessionId, String deviceId, DoorState doorState, Long timestamp, String videoUri) {
        this(sessionId, deviceId, doorState, timestamp, videoUri, null, null, null, null);
    }

    public DoorEventRequest(String sessionId, String deviceId, DoorState doorState, Long timestamp,
                            String videoUri, String uploadStatus, String videoClipsJson, String cameraFusionMode) {
        this(sessionId, deviceId, doorState, timestamp, videoUri, uploadStatus, videoClipsJson, cameraFusionMode, null);
    }
}
