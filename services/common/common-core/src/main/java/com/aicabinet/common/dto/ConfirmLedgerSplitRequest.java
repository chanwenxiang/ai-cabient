package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 确认「仅记账」分账已完结（钱包已入账，无需微信分账通道）。 */
public record ConfirmLedgerSplitRequest(@NotBlank String reason) {}
