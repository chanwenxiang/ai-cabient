package com.aicabinet.common.dto;

/**
 * 未映射外部商品（导入预览/治理用）。
 *
 * @param externalProductId 外部商品 ID（已建档）
 * @param externalKey       外部商品键
 * @param externalName      外部商品名称
 * @param timesSeen         累计出现次数
 * @param suggestedSkuId    名称精确匹配到的本系统 SKU（建议值，可空）
 */
public record ExternalUnmappedProductDto(
        Long externalProductId,
        String externalKey,
        String externalName,
        int timesSeen,
        String suggestedSkuId) {
}
