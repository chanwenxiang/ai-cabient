package com.aicabinet.common.dto;

public record UpdateMerchantDeviceSettingsRequest(
        String deviceName,
        String alertContactName,
        String alertContactPhone,
        Integer targetTempC,
        String opsRemark
) {}
