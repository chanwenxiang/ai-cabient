package com.aicabinet.common.dto;

import java.util.List;

public record MerchantDisputeDetailDto(
        DisputeTicketDto ticket,
        List<DisputeMessageDto> messages,
        boolean canReply
) {}
