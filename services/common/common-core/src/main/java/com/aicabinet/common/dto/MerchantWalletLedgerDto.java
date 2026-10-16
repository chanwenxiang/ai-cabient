package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 商户钱包流水行。🔴 单位口径：本 record 里<b>所有金额都是分</b>，
 * 但 {@code balanceAfter} / {@code frozenAfter} 是历史命名，未带 {@code Cents} 后缀
 * —— 三端（运营台 / 商户端）都按分渲染，改名会动到已生成的 openapi 契约，故只在此处钉单位。
 */
public record MerchantWalletLedgerDto(
        Long ledgerId,
        String merchantId,
        String merchantName,
        String entryType,
        Long amountCents,
        /** 记账后余额（分） */
        Long balanceAfter,
        /** 记账后冻结额（分） */
        Long frozenAfter,
        String refType,
        String refId,
        String remark,
        Instant createdAt
) {
    public MerchantWalletLedgerDto(
            Long ledgerId,
            String merchantId,
            String entryType,
            Long amountCents,
            Long balanceAfter,
            Long frozenAfter,
            String refType,
            String refId,
            String remark,
            Instant createdAt
    ) {
        this(ledgerId, merchantId, null, entryType, amountCents, balanceAfter, frozenAfter,
                refType, refId, remark, createdAt);
    }
}
