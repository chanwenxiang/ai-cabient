package com.aicabinet.common.dto;

/**
 * 「手动生成备货单」预览行：单柜 × 单商品的建议补货量（A 方案，2026-10-29）。
 *
 * <p>currentQty=账面（台账有账看账本、未建账回货道账面，与补货建议同一条口径）；
 * capacity=台账容量（0=未建账）；inTransitQty=已出库未上架的在途抵扣；
 * suggestQty=建议补货量（上限−实际−在途，已按容量钳制）。cases/remainUnits
 * 由前端按 packQty 换算展示（「X 件 + Y 支」，口径同 CB-061）。</p>
 */
public record MerchantPrepPreviewItemDto(
        String skuId,
        String skuName,
        String unit,
        int packQty,
        int currentQty,
        int capacity,
        int inTransitQty,
        int suggestQty
) {}
