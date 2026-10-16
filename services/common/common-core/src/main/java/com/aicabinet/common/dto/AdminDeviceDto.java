package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminDeviceDto(
        String deviceId,
        String deviceName,
        String deviceType,
        /** 投放场景（CB-049）：OFFICE/CAMPUS/...；空=未分类 */
        String sceneType,
        String onlineStatus,
        String merchantId,
        String merchantName,
        String activeSessionId,
        String activeSessionState,
        Instant updatedAt,
        /** 最近上线时间（在线起点；离线=空）。CB-049 扩展展示字段 */
        Instant lastOnlineAt,
        /** 最近离线时间（CB-049 扩展展示字段） */
        Instant lastOfflineAt,
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
        Long id,
        Integer currentTempC,
        Integer targetTempC,
        String firmwareVersion,
        /** 锁机停售原因（运营态文案） */
        String salesLockReason,
        /** 将邑信号强度 0-4（V363；非将邑柜=null）。CB-050 商户端详情展示 */
        Integer signalStrength,
        /** 所属分仓编号；空表示未归线 */
        String homeWarehouseId
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
        this(deviceId, deviceName, deviceType, null, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, null, null, replenishmentInProgress,
                null, null, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
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
        this(deviceId, deviceName, deviceType, null, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, null, null, replenishmentInProgress,
                refundPolicy, effectiveRefundPolicy, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public AdminDeviceDto(
            String deviceId,
            String deviceName,
            String deviceType,
            String sceneType,
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
        this(deviceId, deviceName, deviceType, sceneType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, null, null, replenishmentInProgress,
                refundPolicy, effectiveRefundPolicy, salesLocked,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
