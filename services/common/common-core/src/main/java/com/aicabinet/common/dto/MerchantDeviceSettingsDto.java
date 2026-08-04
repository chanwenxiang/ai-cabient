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
        boolean salesLocked
) {}
