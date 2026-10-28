package com.aicabinet.common.dto;

import java.util.List;

/**
 * 开门柜盘点提交结果：逐行前后账面与差值（正=盘盈/负=盘亏），供前端即时回显。
 */
public record MerchantDoorStocktakeResult(
        String deviceId,
        String refId,
        List<LineResult> lines
) {
    public record LineResult(
            String skuId,
            int quantityBefore,
            int quantityAfter,
            int delta
    ) {
    }
}
