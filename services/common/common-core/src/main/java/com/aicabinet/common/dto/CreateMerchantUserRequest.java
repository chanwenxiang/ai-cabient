package com.aicabinet.common.dto;

public record CreateMerchantUserRequest(
        String phoneNumber,
        String password,
        String displayName,
        String roleKey
) {}
