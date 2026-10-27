package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

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
        String videoPreviewUrl,
        /**
         * 可播地址全集（2026-10-27，CB-030 双摄）：将邑柜每单有左/右两段录像，
         * {@code videoPreviewUrl} 恒为第一个；单摄/旧链路为单元素或空。
         */
        List<String> videoPreviewUrls
) {
    public OpsExceptionDto(
            String exceptionId, String exceptionType, String severity, String status,
            String deviceId, String sessionId, String orderId, Long userId,
            String title, String detail, Long assigneeUserId, String resolution,
            Instant createdAt, Instant updatedAt, Instant resolvedAt
    ) {
        this(exceptionId, exceptionType, severity, status, deviceId, sessionId, orderId, userId,
                title, detail, assigneeUserId, resolution, createdAt, updatedAt, resolvedAt,
                null, false, false, null, null, null);
    }

    /** 兼容旧调用（不带地址全集）：全集回退为单元素列表。 */
    public OpsExceptionDto(
            String exceptionId, String exceptionType, String severity, String status,
            String deviceId, String sessionId, String orderId, Long userId,
            String title, String detail, Long assigneeUserId, String resolution,
            Instant createdAt, Instant updatedAt, Instant resolvedAt,
            Instant slaDueAt, boolean slaOverdue,
            boolean archived, Instant archivedAt,
            String videoPreviewUrl
    ) {
        this(exceptionId, exceptionType, severity, status, deviceId, sessionId, orderId, userId,
                title, detail, assigneeUserId, resolution, createdAt, updatedAt, resolvedAt,
                slaDueAt, slaOverdue, archived, archivedAt,
                videoPreviewUrl,
                videoPreviewUrl == null || videoPreviewUrl.isBlank()
                        ? List.of() : List.of(videoPreviewUrl));
    }
}
