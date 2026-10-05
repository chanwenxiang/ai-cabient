package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 补货任务行提交（小程序「确认任务行」）。
 *
 * <p>审计 P1-15：原 record 零约束注解，{@code @Valid} 空挂 ⇒ 非 RESTOCK 路径
 * quantity 直接入库无校验。{@code @Min(0)} 只拒负数（0 由下游按「行不落地」既有语义处理），
 * 与要货路径（{@code MerchantReplenishmentService} 拒 ≤0）强度对齐。
 */
public record SubmitReplenishmentLinesRequest(
        @NotEmpty(message = "lines required") @Valid List<ReplenishmentTaskLineDto> lines) {}
