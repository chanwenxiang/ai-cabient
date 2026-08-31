package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantDeviceDto(
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
        /** 锁机停售：与运营台 salesLocked 同源 */
        boolean salesLocked,
        String address,
        String routeCode,
        Integer currentTempC,
        Integer targetTempC,
        String lifecycleStatus,
        /** 低库存/缺货货道数（可空，未统计时为 null） */
        Integer lowStockSlotCount,
        Integer oosSlotCount,
        /** 停售原因 */
        String salesLockReason,
        /** 点位坐标（外勤导航） */
        Double latitude,
        Double longitude,
        /** 固件版本 */
        String firmwareVersion
) {

    /** 兼容旧 11 字段构造。 */
    public MerchantDeviceDto(
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
            boolean salesLocked
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress, salesLocked,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public MerchantDeviceDto(
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
            boolean salesLocked,
            String address,
            String routeCode,
            Integer currentTempC,
            Integer targetTempC,
            String lifecycleStatus,
            Integer lowStockSlotCount,
            Integer oosSlotCount
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress, salesLocked,
                address, routeCode, currentTempC, targetTempC, lifecycleStatus,
                lowStockSlotCount, oosSlotCount, null, null, null, null);
    }

    public MerchantDeviceDto(
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
            boolean salesLocked,
            String address,
            String routeCode,
            Integer currentTempC,
            Integer targetTempC,
            String lifecycleStatus,
            Integer lowStockSlotCount,
            Integer oosSlotCount,
            String salesLockReason
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, merchantId, merchantName,
                activeSessionId, activeSessionState, updatedAt, replenishmentInProgress, salesLocked,
                address, routeCode, currentTempC, targetTempC, lifecycleStatus,
                lowStockSlotCount, oosSlotCount, salesLockReason, null, null, null);
    }
}
