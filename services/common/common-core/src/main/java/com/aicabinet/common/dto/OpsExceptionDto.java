package com.aicabinet.common.dto;

import java.time.Instant;

public record OpsExceptionDto(
        String exceptionId, String exceptionType, String severity, String status,
        String deviceId, String sessionId, String orderId, Long userId,
        String title, String detail, Long assigneeUserId, String resolution,
        Instant createdAt, Instant updatedAt, Instant resolvedAt,
        Instant slaDueAt, boolean slaOverdue,
        boolean archived, Instant archivedAt,
        /**
         * 服务端签发的会话录像可播地址：旧边缘 MinIO 预签名，或将邑柜机台账兜底
         * （将邑柜机不写 shopping_session.video_uri，CB-030）。异常复核工作台据此播放。
         */
        String videoPreviewUrl
) {
    public OpsExceptionDto(
            String exceptionId, String exceptionType, String severity, String status,
            String deviceId, String sessionId, String orderId, Long userId,
            String title, String detail, Long assigneeUserId, String resolution,
            Instant createdAt, Instant updatedAt, Instant resolvedAt
    ) {
        this(exceptionId, exceptionType, severity, status, deviceId, sessionId, orderId, userId,
                title, detail, assigneeUserId, resolution, createdAt, updatedAt, resolvedAt,
                null, false, false, null, null);
    }
}
