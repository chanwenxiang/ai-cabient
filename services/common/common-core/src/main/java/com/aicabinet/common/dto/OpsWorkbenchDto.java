package com.aicabinet.common.dto;

import java.util.List;

public record OpsWorkbenchDto(
        long openDisputes,
        long overdueDisputes,
        long offlineDevices,
        long waitingUploads,
        long lowStockItems,
        long pendingReplenishments,
        long staleSessions,
        long reconciliationMismatches,
        long splitExceptions,
        long inTransitOverdue,
        List<OpsActionItemDto> actionItems,
        /** 未锁机（在售）货柜数 */
        long devicesOnSale,
        /** 锁机停售货柜数 */
        long devicesSalesLocked,
        /** 待支付订单数 */
        long pendingUnpaidOrders
) {
    /** 兼容旧调用：无运营态计数 */
    public OpsWorkbenchDto(
            long openDisputes,
            long overdueDisputes,
            long offlineDevices,
            long waitingUploads,
            long lowStockItems,
            long pendingReplenishments,
            long staleSessions,
            long reconciliationMismatches,
            long splitExceptions,
            long inTransitOverdue,
            List<OpsActionItemDto> actionItems
    ) {
        this(openDisputes, overdueDisputes, offlineDevices, waitingUploads, lowStockItems,
                pendingReplenishments, staleSessions, reconciliationMismatches, splitExceptions,
                inTransitOverdue, actionItems, 0, 0, 0);
    }
}
