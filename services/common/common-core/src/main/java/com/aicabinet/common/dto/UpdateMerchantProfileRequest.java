package com.aicabinet.common.dto;

public record UpdateMerchantProfileRequest(
        String contactPhone,
        String alertContactName,
        String alertContactPhone
) {}
