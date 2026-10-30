package com.aicabinet.common.dto;

import java.time.Instant;

public record PullOffTaskDto(
        Long taskId,
        String deviceId,
        String skuId,
        String lotId,
        String batchNo,
        int quantity,
        String reason,
        String status,
        Instant createdAt,
        /** 货道柜的该 SKU 剩余可补容量；为 0 表示应禁用 RESTOCK。开门柜此值恒 0，看 {@link #doorCabinet}。 */
        int restockHeadroom,
        /**
         * 开门柜（判据=将邑绑定，与后端 {@code DeviceSlotService.isDoorCabinet} 同一个）：按商品备货、
         * 无货道概念，RESTOCK <b>不受</b> {@link #restockHeadroom} 门控（CB-051 结论 4 的同一口径）。
         */
        boolean doorCabinet
) {}
