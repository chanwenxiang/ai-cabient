package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 运营对消费者余额的单笔调账请求。
 *
 * <p>R19-6（2026-11-12）：{@code deltaCents} 原先无任何限幅，单笔可写满 int 上界
 * （+21,474,836.47 元）。限幅与同语义的 {@code WalletAdjustRequest} 对齐 ±100 万元；
 * 上限是**自己的设计判断**（消费者余额规模远低于该值），超限走多笔或审批流。
 */
public record AdjustBalanceRequest(
        @Min(value = -100_000_000L, message = "单笔调账不得超过 ±1,000,000.00 元")
        @Max(value = 100_000_000L, message = "单笔调账不得超过 ±1,000,000.00 元")
        int deltaCents,
        @NotBlank String reason,
        @NotBlank @Size(max = 128) String idempotencyKey
) {}
