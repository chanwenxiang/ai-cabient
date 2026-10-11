package com.aicabinet.trade.recognition;

import java.util.List;

/**
 * 一次识别的结算载体（原 {@code RecognitionOutcome}，随旧云识别
 * 客户端删除迁入本包；字段语义不变）。将邑链路由
 * {@code RecognitionNormalizer} 归一化后构造；旧关门路径只产生「待人工审核」占位。
 */
public record RecognitionOutcome(
        String taskId,
        List<RecognizedItem> items,
        float overallConfidence,
        boolean needReview,
        String modelVersion,
        List<String> detectedClasses
) {}
