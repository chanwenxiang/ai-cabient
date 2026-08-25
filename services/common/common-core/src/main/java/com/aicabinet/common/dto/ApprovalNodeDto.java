package com.aicabinet.common.dto;

public record ApprovalNodeDto(
        Long nodeId,
        Integer seq,
        String nodeName,
        String assigneeType,
        String assigneeValue,
        String passRule
) {}
