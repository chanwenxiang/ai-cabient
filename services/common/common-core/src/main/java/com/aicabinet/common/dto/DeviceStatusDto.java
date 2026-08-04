package com.aicabinet.common.dto;

public record DeviceStatusDto(
        String deviceId,
        String deviceName,
        String onlineStatus,
        boolean online,
        boolean available,
        String activeSessionId,
        String activeSessionState,
        /** NONE | SESSION | REPLENISHMENT | LOCKED */
        String busyReason,
        /** 本柜开门预授权门槛（分）：柜机押金优先，否则全局 checkout.preauth_cents */
        int preauthCents
) {}
