package com.aicabinet.common.dto;

public record MerchantDeviceSettingsDto(
        String deviceId,
        String deviceName,
        String deviceType,
        String onlineStatus,
        String address,
        String alertContactName,
        String alertContactPhone,
        Integer targetTempC,
        Integer currentTempC,
        java.time.Instant tempReportedAt,
        boolean tempOutOfRange,
        String opsRemark,
        Boolean tempCommandSent,
        String tempCommandMessage,
        boolean salesLocked,
        String routeCode,
        String lifecycleStatus,
        String salesLockReason,
        Double latitude,
        Double longitude,
        String firmwareVersion
) {

    public MerchantDeviceSettingsDto(
            String deviceId,
            String deviceName,
            String deviceType,
            String onlineStatus,
            String address,
            String alertContactName,
            String alertContactPhone,
            Integer targetTempC,
            Integer currentTempC,
            java.time.Instant tempReportedAt,
            boolean tempOutOfRange,
            String opsRemark,
            Boolean tempCommandSent,
            String tempCommandMessage,
            boolean salesLocked
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, address, alertContactName, alertContactPhone,
                targetTempC, currentTempC, tempReportedAt, tempOutOfRange, opsRemark,
                tempCommandSent, tempCommandMessage, salesLocked, null, null, null, null, null, null);
    }

    public MerchantDeviceSettingsDto(
            String deviceId,
            String deviceName,
            String deviceType,
            String onlineStatus,
            String address,
            String alertContactName,
            String alertContactPhone,
            Integer targetTempC,
            Integer currentTempC,
            java.time.Instant tempReportedAt,
            boolean tempOutOfRange,
            String opsRemark,
            Boolean tempCommandSent,
            String tempCommandMessage,
            boolean salesLocked,
            String routeCode,
            String lifecycleStatus
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, address, alertContactName, alertContactPhone,
                targetTempC, currentTempC, tempReportedAt, tempOutOfRange, opsRemark,
                tempCommandSent, tempCommandMessage, salesLocked, routeCode, lifecycleStatus, null,
                null, null, null);
    }

    public MerchantDeviceSettingsDto(
            String deviceId,
            String deviceName,
            String deviceType,
            String onlineStatus,
            String address,
            String alertContactName,
            String alertContactPhone,
            Integer targetTempC,
            Integer currentTempC,
            java.time.Instant tempReportedAt,
            boolean tempOutOfRange,
            String opsRemark,
            Boolean tempCommandSent,
            String tempCommandMessage,
            boolean salesLocked,
            String routeCode,
            String lifecycleStatus,
            String salesLockReason
    ) {
        this(deviceId, deviceName, deviceType, onlineStatus, address, alertContactName, alertContactPhone,
                targetTempC, currentTempC, tempReportedAt, tempOutOfRange, opsRemark,
                tempCommandSent, tempCommandMessage, salesLocked, routeCode, lifecycleStatus, salesLockReason,
                null, null, null);
    }
}
