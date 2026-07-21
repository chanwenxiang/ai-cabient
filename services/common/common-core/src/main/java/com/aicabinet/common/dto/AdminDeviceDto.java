package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminDeviceDto(
        String deviceId,
        String deviceName,
        String deviceType,
        String onlineStatus,
        String merchantId,
        String merchantName,
        String activeSessionId,
        String activeSessionState,
        Instant updatedAt,
        boolean replenishmentInProgress,
        /** 设备覆盖值：AUTO_REFUND | DISPUTE_ONLY | null=继承 */
        String refundPolicy,
        /** 生效策略（已解析全局默认） */
        String effectiveRefundPolicy,
        /** 锁机停售（运营态） */
        boolean salesLocked
) {
    public AdminDeviceDto(
            String deviceId,
            String deviceName,
            String deviceType,
            String onlineStatus,
            String merchantId,
            String merchantName,
            String activeSessionId,
            String activeSessionState,
            Instant updatedAt,
            boolean replenishmentInProgress
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress, null, null, false);
    }

    public AdminDeviceDto(
            String deviceId,
            String deviceName,
            String deviceType,
            String onlineStatus,
            String merchantId,
            String merchantName,
            String activeSessionId,
            String activeSessionState,
            Instant updatedAt,
            boolean replenishmentInProgress,
            String refundPolicy,
            String effectiveRefundPolicy
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress,
                refundPolicy, effectiveRefundPolicy, false);
    }
}
