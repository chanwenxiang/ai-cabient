package com.aicabinet.common.dto;

import java.util.List;

/**
 * 商户端「手动生成备货单」的单柜预览组（A 方案，2026-10-29）。
 *
 * <p>与首页「备货清单」（{@link MerchantPrepGroupDto}，从进行中任务出库行反推的<b>事后</b>视图）
 * 相对：本预览是<b>事前</b>规划视图——不依赖阈值，按「补满」口径
 * （{@code WarehouseService.suggestForDevice(dev, true)}：上限−实际−在途，
 * 货道无缺口时回落低库存/ROP 建议）逐柜给出建议量。</p>
 *
 * <p>确认后前端逐柜走 {@code POST /replenishment/self-initiate} 落补货任务，
 * 出库/明细/幂等键全部复用既有链路，不新增备货单实体。</p>
 */
public record MerchantPrepPreviewGroupDto(
        String deviceId,
        String deviceName,
        List<MerchantPrepPreviewItemDto> items
) {}
