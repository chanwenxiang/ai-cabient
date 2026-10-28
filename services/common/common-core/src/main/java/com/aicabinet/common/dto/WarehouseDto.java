package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param managerUserId 分仓**主责人**（= {@code managers} 第一位，派生列）。月结「主责人姓名」、
 *                      外部渠道「人员→仓」等单一问责口径读它；权限判据一律看 {@code managers}。
 * @param managers      负责人全集（多人），按绑定先后排序；空列表 = 该仓暂未指定负责人
 */
public record WarehouseDto(
        String warehouseId,
        String warehouseName,
        String address,
        String status,
        Instant createdAt,
        Long managerUserId,
        List<WarehouseManagerDto> managers
) {}
