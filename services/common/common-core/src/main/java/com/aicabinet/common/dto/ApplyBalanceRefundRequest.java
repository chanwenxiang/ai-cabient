package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * MA-089：刻意<b>不加 {@code @Max}</b>。上限由服务端
 * {@code SystemConfigService.BALANCE_REFUND_MAX_CENTS}（DC-6 正数限额键）裁决，
 * 与充值侧 {@code RECHARGE_MAX_CENTS} 同构；DTO 再钉一个硬上限会让「上调限额」这件事
 * 在参数配置里根本调不动（Bean 校验先拦），两处口径互相矛盾。
 */
public record ApplyBalanceRefundRequest(
        @Min(100) int amountCents,
        @Size(max = 200) String reason,
        /** 可选幂等键（V343）：客户端对同一提交意图保持不变；同键重放返回原单。≤64 字符。 */
        @Size(max = 64) String idemKey
) {}
