package com.aicabinet.common.dto;

/** 消费者服务号通知绑定状态；不返回 openid。 */
public record WeChatOfficialBindingStatusDto(boolean configured, boolean bound) {}
