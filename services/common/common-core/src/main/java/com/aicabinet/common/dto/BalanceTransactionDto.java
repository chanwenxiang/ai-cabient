package com.aicabinet.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "余额流水（消费者账单明细）")
public record BalanceTransactionDto(
        @Schema(description = "流水号（= 支付操作号 operationId）")
        String transactionId,

        @Schema(description = "用户 ID")
        Long userId,

        @Schema(description = "业务类型：充值/扣款/退款/预授权冻结与释放等（如 CHARGE、ADJUST_CHARGE、PREAUTH_FREEZE、PREAUTH_RELEASE）")
        String businessType,

        @Schema(description = "关联业务单号（通常为订单号；纯冻结/释放类流水可能为空）")
        String businessId,

        @Schema(description = "带符号金额（分）：正数=入账，负数=出账；纯冻结/释放按业务方向取符号")
        int amountCents,

        @Schema(description = "变动前可用余额（分）")
        int balanceBeforeCents,

        @Schema(description = "变动后可用余额（分）")
        int balanceAfterCents,

        @Schema(description = "变动原因/备注")
        String reason,

        @Schema(description = "发生时间")
        Instant createdAt
) {}
