package com.aicabinet.common.dto;

import java.util.List;

public record DevRecognitionPreviewDto(
        List<DevRecognitionItemDto> items,
        List<String> detectedClasses,
        float overallConfidence,
        boolean needReview,
        String modelVersion,
        String hint
) {}
