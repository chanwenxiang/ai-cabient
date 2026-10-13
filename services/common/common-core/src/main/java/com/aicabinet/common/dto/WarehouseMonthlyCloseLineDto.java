package com.aicabinet.common.dto;

/**
 * 分仓月结一行（件数，不是金额）。
 *
 * @param countedQty 本账期最近一次已完成盘点的实盘件数；未盘则为 null（V348 账期制 26~25）
 * @param rockLossQty 摇机损耗（V348：DAMAGED 核销自动归集 + 草稿手工补录）
 * @param expiredLossQty 过期损耗（V348：EXPIRED 核销自动归集）
 * @param guestWaterQty 客情用水（V348：外部导入 GUEST_WATER 流水自动归集）
 * @param feeDeductQty 费用抵扣（V348：草稿阶段手工补录）
 * @param unitPriceCents 单价快照 = 目录采购成本（未配置为 null；V348）
 */
public record WarehouseMonthlyCloseLineDto(
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
        int rockLossQty,
        int expiredLossQty,
        int guestWaterQty,
        int feeDeductQty,
        Long unitPriceCents
) {}
