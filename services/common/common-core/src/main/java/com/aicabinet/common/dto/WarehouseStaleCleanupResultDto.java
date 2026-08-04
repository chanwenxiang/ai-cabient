package com.aicabinet.common.dto;

import java.util.List;

/**
 * 仓库脏出库安全收口结果：仅走 cancel-unreceived，不硬删业务行。
 */
public record WarehouseStaleCleanupResultDto(
        int cancelledEmptyDrafts,
        int cancelledTerminalDrafts,
        int cancelledOrphanShipped,
        int skipped,
        List<Long> cancelledOutboundIds
) {}
