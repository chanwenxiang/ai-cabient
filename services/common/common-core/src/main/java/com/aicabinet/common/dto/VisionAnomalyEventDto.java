package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 端侧视觉异常事件（移远 OpenVending 等端侧识别上报）。
 *
 * @param deviceId   柜机编码
 * @param sessionId  关联开门会话（可为空，如非会话内的防撬事件）
 * @param eventType  ITEM_MISPLACE=商品错拿；OCCLUSION=遮挡识别；TAMPER=防撬；ABNORMAL_OPEN=异常开门
 * @param confidence 事件置信度（0~1）
 * @param detail     附加描述（如 SKU、画面信息）
 * @param provider   识别提供商（QUECTEL / YOLO_EDGE 等）
 * @param occurredAt 事件发生时间
 */
public record VisionAnomalyEventDto(
        String deviceId,
        String sessionId,
        String eventType,
        double confidence,
        String detail,
        String provider,
        Instant occurredAt
) {}
