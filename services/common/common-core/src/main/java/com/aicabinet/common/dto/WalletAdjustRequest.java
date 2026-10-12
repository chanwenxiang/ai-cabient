package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 钱包调账请求体（运营侧 ops:merchant-withdraw:adjust）。
 * 金额必须显式传入且限幅 ±1,000,000.00 元：
 * 旧实现裸 Map longValue() 无界，Long.MIN_VALUE 取负仍为负 ⇒ 扣款语义反转（三方扫描 P2）。
 * 限幅值是自己的设计判断（本项目单商户钱包规模远低于该上限），超限走多笔或审批流。
 */
public record WalletAdjustRequest(
        @NotNull
        @Min(value = -100_000_000L, message = "单笔调账不得超过 ±1,000,000.00 元")
        @Max(value = 100_000_000L, message = "单笔调账不得超过 ±1,000,000.00 元")
        Long amountCents,
        @Size(max = 200) String remark
) {}
