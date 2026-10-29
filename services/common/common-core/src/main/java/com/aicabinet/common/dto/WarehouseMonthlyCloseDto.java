package com.aicabinet.common.dto;

import java.util.List;

/**
 * 分仓月结一张表：应有 / 实盘 / 上柜，与结算 GMV 分开。
 *
 * <p>V348 账期制：账期 = 上月 26 日 00:00 ~ 本月 25 日 24:00，{@code yearMonth} = 结束月标签。
 *
 * @param managerUserName 分仓主责人姓名（{@code warehouse.manager_user_id} → user_info.name，未绑定为 null）。
 *                        🔴 仅作归属展示：CB-068（2026-10-27 拍板）已把该字段语义降级为「主责/默认派单人」，
 *                        不得当权限闸，仓库可见范围的真源是商户树 + {@code ops_user_device_scope}
 * @param deviceCount     归属该仓的柜机台数（按 {@code device_info.home_warehouse_id} 统计）
 */
public record WarehouseMonthlyCloseDto(
        String warehouseId,
        String warehouseName,
        String managerUserName,
        int deviceCount,
        String yearMonth,
        String formulaHint,
        String openingSource,
        List<WarehouseMonthlyCloseLineDto> lines
) {}
