package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * 仓库月结单（V327，E7；竞品口径 CB-017 两步法）。
 *
 * @param closeId           单据 ID
 * @param warehouseId       仓库 ID
 * @param warehouseName     仓库名
 * @param yearMonth         月结月份（yyyy-MM，Asia/Shanghai 口径——与既有 closeSheet 一致）
 * @param status            DRAFT / APPROVED（APPROVED 后锁单不可改删）
 * @param lossQty           盘亏件数合计（gap&lt;0 行）
 * @param surplusQty        盘盈件数合计（gap&gt;0 行）
 * @param lossAmountCents   盘亏金额合计（成本未配置的行不计入，行级留空提示）
 * @param surplusAmountCents 盘盈金额合计
 * @param lineCount         行数
 * @param approvedByName    审批人姓名（DRAFT 为 null）
 * @param approvedAt        审批时间
 * @param remark            备注
 * @param createdAt         生成时间
 * @param lines             明细行
 */
public record WarehouseCloseSheetDto(
        Long closeId,
        String warehouseId,
        String warehouseName,
        String yearMonth,
        String status,
        int lossQty,
        int surplusQty,
        long lossAmountCents,
        long surplusAmountCents,
        int lineCount,
        String approvedByName,
        Instant approvedAt,
        String remark,
        Instant createdAt,
        List<WarehouseCloseSheetLineDto> lines) {
}
