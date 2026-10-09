package com.aicabinet.common.dto;

import java.util.List;

/**
 * 设备详情页「将邑接入」卡片一次拉全的视图（CB-022）。
 *
 * <p>binding 为 null = 该柜机尚未登记为将邑柜（普通柜机展示「未接入」态）；
 * mappings 为该柜机 class_id→SKU 映射全量（含 DISABLED，后台要能看全再启停）。</p>
 */
public record JiangyiBindingViewDto(
        JiangyiDeviceDto binding,
        List<JiangyiClassMappingDto> mappings
) {}
