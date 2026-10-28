package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 开门柜盘点提交（2026-10-28）：逐商品实盘覆盖式调账。
 * <p>实盘数即最终数：服务端按 台账→实盘 差值自动加/减（多了补 STOCKTAKE 批次、
 * 少了按 FEFO 扣减），批次/台账/聚合三层账一起动。无弹簧柜货道概念。</p>
 */
public record MerchantDoorStocktakeRequest(
        @NotEmpty @Valid List<StocktakeLine> lines,
        /** 盘点照片凭证；商户开启 photoStocktake 时必填 */
        String photoEvidenceUrl,
        /** 盘点定位（可选，凭证防伪，写入盘点事件 detail） */
        Double latitude,
        Double longitude
) {
    /** 单商品实盘行。countedQuantity = 柜里数到的总数（覆盖式，非增量）。 */
    public record StocktakeLine(
            @NotBlank String skuId,
            @Min(0) int countedQuantity
    ) {
    }
}
