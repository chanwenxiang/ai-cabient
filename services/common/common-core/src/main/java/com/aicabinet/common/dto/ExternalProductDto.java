package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 外部商品（治理列表行，CB-036）。
 *
 * @param externalProductId 外部商品 ID
 * @param channelCode       渠道码
 * @param externalKey       外部商品键
 * @param externalName      外部商品名称
 * @param brand             品牌
 * @param spec              规格
 * @param unitPriceCents    最近一次单价（分）
 * @param timesSeen         累计出现次数
 * @param lastSeenAt        最近出现时间
 * @param mappedSkuId       已映射本系统 SKU（可空=未映射）
 * @param mappedSkuName     已映射 SKU 名称
 */
public record ExternalProductDto(
        Long externalProductId,
        String channelCode,
        String externalKey,
        String externalName,
        String brand,
        String spec,
        Long unitPriceCents,
        int timesSeen,
        Instant lastSeenAt,
        String mappedSkuId,
        String mappedSkuName) {
}
