package com.aicabinet.common.dto;

public record PayContractDto(
        String channel,
        boolean active,
        String contractId,
        String message
) {}
