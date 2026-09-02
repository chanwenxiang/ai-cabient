package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record PurchaseOrderDto(
        Long purchaseOrderId,
        String supplierId,
        String warehouseId,
        String status,
        String refNo,
        Long operatorId,
        String notes,
        Instant createdAt,
        Instant receivedAt,
        List<PurchaseOrderLineDto> lines,
        /** 待审批时当前节点名称（如「财务审批」） */
        String approvalCurrentNodeName,
        /** 当前登录用户是否为该节点待办处理人 */
        Boolean approvalPendingForMe
) {}
