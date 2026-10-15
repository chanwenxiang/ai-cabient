package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * C2 理货记录台账行（对齐老系统 stock_operator_record 语义：
 * 谁（操作员）在哪个柜（machine）动了什么货（sku）动了几件（quantity））。
 */
public record TallyRecordRowDto(
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
