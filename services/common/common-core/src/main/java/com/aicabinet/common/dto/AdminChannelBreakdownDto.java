package com.aicabinet.common.dto;

import java.util.List;

/** 订单支付渠道 + 充值渠道分布 */
public record AdminChannelBreakdownDto(
        List<AdminChannelStatDto> orderPayChannels,
        List<AdminChannelStatDto> rechargeChannels
) {}
