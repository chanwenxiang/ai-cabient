package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提现申请请求体（ops 侧 withdraw 与商户端 wallet/line-wallet withdraw 共用）。
 * amountCents 必须 > 0；merchantId 仅商户端多绑定场景使用（可空）。
 */
public record WithdrawApplyRequest(
        @NotNull
        @Positive
        Long amountCents,
        @Size(max = 64) String requestNo,
        @Size(max = 32) String merchantId
) {}
