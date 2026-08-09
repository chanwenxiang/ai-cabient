package com.aicabinet.common.dto;

/** 通知外部渠道分发消息（微信订阅消息 / 短信），由异步队列或同步网关消费。 */
public record NotificationDispatchMessage(
        String templateCode,
        Long userId,
        String title,
        String body,
        String bizType,
        String bizId
) {}
