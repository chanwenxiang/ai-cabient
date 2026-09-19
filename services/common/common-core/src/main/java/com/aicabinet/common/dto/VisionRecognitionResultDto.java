package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * 端侧识别结果直报（第三方端侧盒子完成识别后上报，复用既有结算链路）。
 *
 * <p>字段命名与 vision-service 回写 Kafka 的识别结果 payload 完全一致，
 * 使端侧既可直接投递 {@code aicabinet.vision.recognize.result}，也可走 HTTP 直报，
 * 两种入口对同一份结果语义相同。
 *
 * @param sessionId        开门会话号（必填，结算主体）
 * @param taskId           端侧识别任务号（可空，落库用于对账/排查）
 * @param traceId          端侧链路追踪号（可空，用于跨端串联日志）
 * @param items            识别到的商品行；识别为空时传空数组或 null
 * @param overallConfidence 整体置信度（0~1）
 * @param needReview       端侧是否自认需要人工复核（true 时平台一律不静默扣款）
 * @param modelVersion     端侧识别模型/固件版本（**必填**，平台据其判定是否生产精度）
 * @param detectedClasses  端侧检出的原始类名（可空，用于类名→SKU 映射排查）
 * @param provider         识别提供商标识（如 QUECTEL）
 * @param occurredAt       端侧识别完成时间（可空）
 */
public record VisionRecognitionResultDto(
        String sessionId,
        String taskId,
        String traceId,
        List<Item> items,
        double overallConfidence,
        boolean needReview,
        String modelVersion,
        List<String> detectedClasses,
        String provider,
        Instant occurredAt
) {
    /**
     * 单行识别结果。
     *
     * @param skuId      SKU 编码（必填非空）
     * @param quantity   数量（必须 &gt; 0）
     * @param confidence 该行置信度（0~1）
     */
    public record Item(String skuId, int quantity, double confidence) {}
}
