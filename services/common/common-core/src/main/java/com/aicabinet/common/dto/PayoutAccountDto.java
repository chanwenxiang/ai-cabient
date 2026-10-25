package com.aicabinet.common.dto;

import java.time.Instant;

/** 收款账户（只含掩码，绝不含明文账号/密文——CB-066 三铁律之一）。 */
public record PayoutAccountDto(
        Long accountId,
        String accountType,
        String channel,
        String accountName,
        String accountNoMask,
        String bankName,
        String bankBranch,
        String taxNo,
        Boolean isDefault,
        String status,
        Instant createdAt
) {}
