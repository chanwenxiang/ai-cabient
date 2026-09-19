package com.aicabinet.common.dto;

/** 功能开关下拉型（SELECT）的候选项。 */
public record FeatureFlagOptionDto(
        String value,
        String label
) {}
