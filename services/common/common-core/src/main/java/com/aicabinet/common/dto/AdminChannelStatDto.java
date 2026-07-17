package com.aicabinet.common.dto;

/** 支付/充值渠道汇总 */
public record AdminChannelStatDto(
        String channel,
        long count,
        long amountCents
) {}
