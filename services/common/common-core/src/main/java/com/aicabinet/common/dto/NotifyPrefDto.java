package com.aicabinet.common.dto;

public record NotifyPrefDto(
        String category,
        String label,
        boolean enabled
) {}
