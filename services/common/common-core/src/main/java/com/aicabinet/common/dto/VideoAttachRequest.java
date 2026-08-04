package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record VideoAttachRequest(
        @NotBlank String sessionId,
        @NotBlank String deviceId,
        @NotBlank String videoUri,
        String uploadStatus,
        String videoClipsJson,
        String cameraFusionMode
) {
    public VideoAttachRequest(String sessionId, String deviceId, String videoUri) {
        this(sessionId, deviceId, videoUri, "UPLOADED", null, null);
    }
}
