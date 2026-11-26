package com.aicabinet.common.dto;

/** 服务号网页授权一次性票据。 */
public record WeChatOfficialBindTicketDto(String authorizeUrl, int expiresInSeconds) {}
