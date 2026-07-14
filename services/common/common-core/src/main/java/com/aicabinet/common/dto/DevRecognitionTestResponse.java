package com.aicabinet.common.dto;

public record DevRecognitionTestResponse(
        SessionDto session,
        OrderDto order,
        String videoUri,
        String hint,
        DevRecognitionPreviewDto recognition
) {}
