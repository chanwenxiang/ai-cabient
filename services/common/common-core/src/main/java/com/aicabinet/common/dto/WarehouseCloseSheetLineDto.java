package com.aicabinet.common.dto;

/**
 * 仓库月结单明细行（V327；V348 账期制扩展）。
 *
 * <p>差异处置（CB-017 两步法）：{@code PENDING} 待认定 / {@code CLAIM} 已进索赔台账
 * （{@code claimWriteOffId} 关联 {@code inventory_write_off}）/ {@code NORMAL_LOSS} 正常损耗 /
 * {@code SURPLUS} 盘盈待查。处置单向不可逆（CLAIM 已挂账）。
 *
 * @param gapAmountCents 差异金额 = |gap| × 单价快照（成本未配置为 null）
 * @param rockLossQty 摇机损耗（V348）
 * @param expiredLossQty 过期损耗（V348）
 * @param guestWaterQty 客情用水（V348）
 * @param feeDeductQty 费用抵扣（V348）
 * @param unitPriceCents 单价快照 = 目录采购成本（未配置为 null；V348）
 * @param editedFlag 草稿阶段人工编辑过（漂移核对跳过该行；V348）
 * @param lineRemark 行备注（人工补录说明；V348）
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
        Long claimWriteOffId,
        int rockLossQty,
        int expiredLossQty,
        int guestWaterQty,
        int feeDeductQty,
        Long unitPriceCents,
        boolean editedFlag,
        String lineRemark) {
}
