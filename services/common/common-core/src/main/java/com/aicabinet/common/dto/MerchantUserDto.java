package com.aicabinet.common.dto;

public record MerchantUserDto(
        Long userId,
        String phoneNumber,
        String displayName,
        String roleKey,
        String roleName,
        String status,
        boolean self
) {}
