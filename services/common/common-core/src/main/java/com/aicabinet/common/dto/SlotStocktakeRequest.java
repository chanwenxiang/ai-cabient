package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SlotStocktakeRequest(
        @NotBlank String slotCode,
        @Min(0) int physicalQty,
        /** true：按实盘数量回写该货道绑定 SKU 的账面批次库存 */
        Boolean adjustBookQty,
        /** 盘点照片凭证；商户开启 photoStocktake 时必填 */
        String photoEvidenceUrl,
        /** 盘点定位（可选，CB-050 扩展：凭证防伪，写入盘点事件 detail） */
        Double latitude,
        Double longitude
) {
    public SlotStocktakeRequest(String slotCode, int physicalQty, Boolean adjustBookQty) {
        this(slotCode, physicalQty, adjustBookQty, null, null, null);
    }

    public SlotStocktakeRequest(String slotCode, int physicalQty, Boolean adjustBookQty, String photoEvidenceUrl) {
        this(slotCode, physicalQty, adjustBookQty, photoEvidenceUrl, null, null);
    }
}
