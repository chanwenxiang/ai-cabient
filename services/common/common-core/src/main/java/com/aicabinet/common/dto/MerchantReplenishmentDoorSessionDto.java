package com.aicabinet.common.dto;

/**
 * 商户补货开门会话状态（以服务端补货会话为准）。
 *
 * @param doorOpened 是否已存在绑定该任务的开门会话
 * @param sessionId  会话 ID（未开门时为 null）
 * @param state      会话状态名（未开门时为 null）
 */
public record MerchantReplenishmentDoorSessionDto(
        boolean doorOpened,
        String sessionId,
        String state
) {}
