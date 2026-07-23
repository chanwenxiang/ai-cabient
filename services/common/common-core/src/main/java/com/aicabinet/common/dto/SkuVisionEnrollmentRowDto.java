package com.aicabinet.common.dto;

/**
 * 商品入驻列表行：主数据 + 运营侧映射是否生效 + 模型管线 stub 状态。
 */
public record SkuVisionEnrollmentRowDto(
        SkuCatalogDto sku,
        boolean mappingEffective,
        String modelPipelineStatus,
        String nextAction,
        String nextStatus
) {}
