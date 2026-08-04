package com.aicabinet.common.dto;

public record UpdateMerchantUserRequest(
        String displayName,
        String roleKey
) {}
