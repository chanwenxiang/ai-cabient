package com.aicabinet.common.dto;

public record DeviceStatusDto(
        String deviceId,
        String deviceName,
        String onlineStatus,
        boolean online,
        boolean available,
        String activeSessionId,
        String activeSessionState,
        /** NONE | SESSION | REPLENISHMENT */
        String busyReason
) {}
