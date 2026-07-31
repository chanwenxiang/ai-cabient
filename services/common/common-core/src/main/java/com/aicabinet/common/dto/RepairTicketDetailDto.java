package com.aicabinet.common.dto;

import java.util.List;

public record RepairTicketDetailDto(
        RepairTicketDto ticket,
        List<RepairTicketEventDto> events
) {}
