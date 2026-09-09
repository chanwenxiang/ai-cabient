package com.aicabinet.common.dto;

/** 运营识别测试闭环结果。 */
public record DevRecognitionTestResponse(
        SessionDto session,
        OrderReadModel order,
        String videoUri,
        String hint,
        DevRecognitionPreviewDto recognition
) {}
