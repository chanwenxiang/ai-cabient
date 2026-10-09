package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 将邑 class_id → SKU 映射（CB-022，运营后台展示用）。
 *
 * <p>识别上报只带 classId（无金额、无 SKU），结算前必须经本映射换算成我方 SKU；
 * 未命中即 fail-closed 转 DISPUTED（宁可人工介入，不算错账）。
 * status：ACTIVE / DISABLED（停用即时生效）；source：MANUAL（后台人工）/ MODEL_SYNC（模型同步预生成）。</p>
 */
public record JiangyiClassMappingDto(
        Long id,
        String deviceId,
        Integer classId,
        String modelName,
        String textName,
        String skuId,
        String status,
        String source,
        Instant createdAt,
        Instant updatedAt
) {}
