package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SlotStocktakeRequest(
        @NotBlank String slotCode,
        @Min(0) int physicalQty,
        /** true：按实盘数量回写该货道绑定 SKU 的账面批次库存 */
        Boolean adjustBookQty,
        /** 盘点照片凭证；商户开启 photoStocktake 时必填 */
        String photoEvidenceUrl
) {
    public SlotStocktakeRequest(String slotCode, int physicalQty, Boolean adjustBookQty) {
        this(slotCode, physicalQty, adjustBookQty, null);
    }
}
