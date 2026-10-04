package com.aicabinet.common.dto;

import java.util.List;

/** 分仓月结一张表：应有 / 实盘 / 上柜，与结算 GMV 分开。 */
public record WarehouseMonthlyCloseDto(
        String warehouseId,
        String warehouseName,
        String yearMonth,
        String formulaHint,
        List<WarehouseMonthlyCloseLineDto> lines
) {}
