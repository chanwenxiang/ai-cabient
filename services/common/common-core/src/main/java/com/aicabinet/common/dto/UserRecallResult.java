package com.aicabinet.common.dto;

public record UserRecallResult(
        int issuedCount,
        int notifiedCount
) {}
