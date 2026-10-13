package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ApplyBalanceRefundRequest(
        @Min(100) @Max(500_000) int amountCents,
        @Size(max = 200) String reason,
        /** 可选幂等键（V343）：客户端对同一提交意图保持不变；同键重放返回原单。≤64 字符。 */
        @Size(max = 64) String idemKey
) {}
