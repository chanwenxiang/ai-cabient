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
 * @param barcode 69 条码（目录未配置为 null）——月结表把它单列展示，商品名不再和 skuId 挤在一格：
 *                {@code skuId} 是业务主键，演示品是 {@code SKU-DEMO-001} 这类字符串、厂商导入品是
 *                {@code 100013} 这类数字串，两种形态混在一列运营读不出归属
 */
public record WarehouseMonthlyCloseLineDto(
        String skuId,
        String skuName,
        String barcode,
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
