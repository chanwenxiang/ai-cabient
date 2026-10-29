package com.aicabinet.common.dto;

/**
 * D28-A5（CB-075）：开门柜「这台柜这个品最多还能补几件」的四要素与结论，一次下发。
 *
 * <p>由服务端唯一出处 {@code ReplenishmentService} 计算，提交校验与小程序录入共用同一个数——
 * 前端此前只算「上限 − 在架」，少扣一层他单已领未上柜，才会「能加进去、点提交才 400」。
 *
 * @param skuId            商品
 * @param capacity         台账容量（水位上限）
 * @param onShelfQty       当前在架量
 * @param otherInTransitQty 他单已领未上柜（排除本任务出库单，见 CB-075 结论 ②）
 * @param room             可补量 = max(0, capacity − onShelfQty − otherInTransitQty)
 */
public record ReplenishmentHeadroomDto(
        String skuId,
        int capacity,
        int onShelfQty,
        int otherInTransitQty,
        int room
) {}
