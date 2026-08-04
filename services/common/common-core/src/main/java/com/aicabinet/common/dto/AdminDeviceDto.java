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
        boolean salesLocked,
        String lifecycleStatus,
        String imei,
        String assetOwner,
        String coopMode,
        Long depositCents,
        Long dataFeeCents,
        String opsTags,
        String routeCode,
        Instant deployedAt,
        String lifecycleRemark,
        Double latitude,
        Double longitude,
        String address,
        /** 库表自增数字 ID（展示用，业务主键仍为 deviceId） */
        Long id
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
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress,
                null, null, false, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
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
                refundPolicy, effectiveRefundPolicy, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
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
            String effectiveRefundPolicy,
            boolean salesLocked
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress,
                refundPolicy, effectiveRefundPolicy, salesLocked,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
