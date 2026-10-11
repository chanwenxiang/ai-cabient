package com.aicabinet.common.dto;

import com.aicabinet.common.enums.DoorState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 门事件。旧链的 videoUri/uploadStatus/videoClipsJson/cameraFusionMode 四字段已随
 * 旧视频链退役（2026-10-11 批6）——将邑柜视频走 OSS 台账（jiangyi_order_video），
 * gravityDeltasJson 保留（本地演示/重力兜底基建）。
 */
public record DoorEventRequest(
        @NotBlank String sessionId,
        @NotBlank String deviceId,
        @NotNull DoorState doorState,
        Long timestamp,
        String gravityDeltasJson
) {
    public DoorEventRequest(String sessionId, String deviceId, DoorState doorState, Long timestamp) {
        this(sessionId, deviceId, doorState, timestamp, null);
    }
}
