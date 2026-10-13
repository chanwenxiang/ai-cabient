package com.aicabinet.common.dto;

import java.util.List;

/**
 * 分仓月结一张表：应有 / 实盘 / 上柜，与结算 GMV 分开。
 *
 * <p>V348 账期制：账期 = 上月 26 日 00:00 ~ 本月 25 日 24:00，{@code yearMonth} = 结束月标签。
 */
public record WarehouseMonthlyCloseDto(
        String warehouseId,
        String warehouseName,
        String yearMonth,
        String formulaHint,
        String openingSource,
        List<WarehouseMonthlyCloseLineDto> lines
) {}
