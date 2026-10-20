package com.aicabinet.common.dto;

import java.util.Map;

/**
 * 通知外部渠道分发消息（微信订阅消息 / 短信），由异步队列或同步网关消费。
 *
 * <p>{@code smsParams} 是短信渠道的**结构化参数**（可选）：阿里云通知短信强制要求模板渲染，
 * 自由文本 title/body 无法直接落模板变量，故业务方在创建消息时把模板所需的键值对一并带上；
 * 为 null 时短信渠道回退为 webhook 文本直发（兼容既有催缴/通知路径）。Kafka JSON 序列化
 * 增字段向后兼容（旧消费者忽略未知字段）。</p>
 */
public record NotificationDispatchMessage(
        String templateCode,
        Long userId,
        String title,
        String body,
        String bizType,
        String bizId,
        Map<String, String> smsParams
) {
    /** 兼容旧签名（无结构化短信参数 → webhook 文本通道）。 */
    public NotificationDispatchMessage(String templateCode, Long userId, String title,
                                       String body, String bizType, String bizId) {
        this(templateCode, userId, title, body, bizType, bizId, null);
    }
}
