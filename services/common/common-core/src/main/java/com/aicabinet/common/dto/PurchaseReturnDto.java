package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record PurchaseReturnDto(
        Long returnId,
        Long purchaseOrderId,
        String warehouseId,
        String supplierId,
        String status,
        String notes,
        /**
         * V318：退货原因分类。null = 未分类（**要治理的状态**，不等于 OTHER）。
         *
         * <p>🔴 刻意让 DTO 带上它：只存不返回的话前端看不到，
         * 运营就无从知道「填了没有」⇒ 分类会退化成永远为空的死字段。
         */
        String reasonCategory,
        /** V318：责任方。null = 尚未认定（与 NONE「认定无责任方」不同）。 */
        String responsibleParty,
        /** V318：是否残次品。null = 未标记。 */
        Boolean defective,
        Long operatorId,
        Instant createdAt,
        List<PurchaseReturnLineDto> lines
) {}
