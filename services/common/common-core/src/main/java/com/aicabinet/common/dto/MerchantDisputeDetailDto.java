package com.aicabinet.common.dto;

import java.util.List;

public record MerchantDisputeDetailDto(
        DisputeTicketDto ticket,
        List<DisputeMessageDto> messages,
        boolean canReply,
        boolean canResolve
) {
    /** 兼容旧三参构造。 */
    public MerchantDisputeDetailDto(DisputeTicketDto ticket, List<DisputeMessageDto> messages, boolean canReply) {
        this(ticket, messages, canReply, false);
    }
}
