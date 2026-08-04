package com.aicabinet.common.dto;

public record RecognitionComputeDto(
        String merchantId,
        long remaining,
        long cumulative,
        long used
) {}
