package com.aicabinet.common.dto;

/**
 * 仓库月结单明细行（V327）。
 *
 * <p>差异处置（CB-017 两步法）：{@code PENDING} 待认定 / {@code CLAIM} 已进索赔台账
 * （{@code claimWriteOffId} 关联 {@code inventory_write_off}）/ {@code NORMAL_LOSS} 正常损耗 /
 * {@code SURPLUS} 盘盈待查。处置单向不可逆（CLAIM 已挂账）。
 *
 * @param gapAmountCents 差异金额 = |gap| × 目录采购成本（成本未配置为 null）
 */
public record WarehouseCloseSheetLineDto(
        Long lineId,
        String skuId,
        String skuName,
        int openingQty,
        int purchaseInQty,
        int transferInQty,
        int transferOutQty,
        int restockQty,
        int returnQty,
        int lossQty,
        int expectedQty,
        Integer countedQty,
        Integer gapQty,
        Long gapAmountCents,
        String gapDisposition,
        Long claimWriteOffId) {
}
