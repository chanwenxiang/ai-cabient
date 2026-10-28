package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * C2 理货记录台账行（对齐老系统 stock_operator_record 语义：
 * 谁（操作员）在哪个柜（machine）动了什么货（sku）动了几件（quantity））。
 *
 * <p>🔴 {@code lineId} 是行身份（replenishment_task_line 主键）：一行=一个补货任务行，
 * 开门柜的 {@code slotId} 恒为 null，前端若用 {@code taskId:skuId:slotId} 当 row-key，
 * 同任务同 SKU 的两行会撞 key（Vue 复用节点、勾选/展开错行）。
 */
public record TallyRecordRowDto(
        Long lineId,
        Long taskId,
        String deviceId,
        Long operatorUserId,
        String operatorName,
        String taskStatus,
        String lineType,
        String skuId,
        String skuName,
        Integer quantity,
        String slotId,
        Boolean applied,
        Instant completedAt
) {}
