package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 将邑 class_id → SKU 映射（CB-022，运营后台展示用）。
 *
 * <p>识别上报按 <b>thirdPid（商品库挂接）优先、classId 映射兜底</b> 换算成我方 SKU
 * （2026-10-21 修订：固件实发 V16 §5.2.9 富字段形态、上报带 thirdPid，并非「只带 classId」；
 * 本表是兜底通道，见 {@code docs/JIANGYI_GATEWAY_DESIGN.md} §0 与 CB-062）。
 * 两者都未命中即 fail-closed 转 DISPUTED（宁可人工介入，不算错账）。
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
