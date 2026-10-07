package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record StocktakeLineDto(
        Long lineId,
        Long stocktakeId,
        String skuId,
        String skuName,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int bookQty,
        Integer countedQty,
        int diffQty,
        String status,
        String notes,
        /**
         * V312：盘点差异原因分类。
         *
         * <p>null = 未分类（要治理的问题），<b>不等于</b> {@code OTHER}。
         * 与 {@code inventory_write_off.reason_category} 是两条链路（账实不符 vs 主动核销）。
         */
        String diffReason,
        Instant adjustedAt
) {}
