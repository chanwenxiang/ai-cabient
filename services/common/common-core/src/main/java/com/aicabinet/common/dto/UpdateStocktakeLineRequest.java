package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;

/**
 * V312：录入盘点实盘数。
 *
 * @param countedQty 实盘数量
 * @param notes 备注（自由文本）
 * @param diffReason
 *        V312 新增 —— 盘点差异原因分类（见 {@code StocktakeDiffReason}）。
 *        <p>🔴 <b>刻意不设为必填</b>：强制填写会让人为了过校验而随便选一个，
 *        那比「没有分类」更坏 —— 假数据会让「某仓反复盘亏」这类异常模式彻底看不出来。
 *        <p>不填 = 未分类，是要治理的问题（可统计有多少行未分类）。
 *        <p>⚠️ 填了就会校验<b>方向一致性</b>：盘盈行填盘亏分类 ⇒ 400。
 *        因为方向填反时统计会把「货多了」算成「货少了」，报表上很难发现。
 */
public record UpdateStocktakeLineRequest(
        @NotNull Integer countedQty,
        String notes,
        String diffReason
) {}