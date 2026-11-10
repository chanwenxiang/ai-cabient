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
        /**
         * 本柜**最近一张**补货任务的状态（无任务时 null）。
         *
         * <p>商户端「手动生成备货单」判「改量重提」要用：同一柜上次成功提交后，前端记着明细指纹，
         * 指纹变了就拦下并让用户去任务页改量（后端按 clientRequestNo 回放不核对明细，直接重提会
         * 静默回放旧数量）。但那张任务若已取消/已完成，改量重提就是新意图，继续拦会把用户锁死
         *（提示去任务页改量，而任务页里已经没有可改的任务）——R2-B1 残留。</p>
         */
        String lastTaskStatus,
        List<MerchantPrepPreviewItemDto> items
) {}
