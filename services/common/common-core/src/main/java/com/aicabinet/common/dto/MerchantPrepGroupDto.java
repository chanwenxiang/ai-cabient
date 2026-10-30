package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * 商户首页「备货清单」的柜机模块（CB-072 / CB-073）。
 *
 * <p>备货是「照这台柜子的单去仓库拣货」：跨柜合并 SKU 会丢掉「这批货给谁」与分仓口径
 * （每台柜的出库单各自挂自己的分仓），故清单按柜机归组，组内再按 SKU 聚合。</p>
 */
public record MerchantPrepGroupDto(
        String deviceId,
        String deviceName,
        /** 本柜待备货的任务数（PENDING + IN_PROGRESS） */
        int taskCount,
        /** 本柜最近一张补货任务的创建时间——备货单「已生成」按它排序（无任务时为 null） */
        Instant lastTaskAt,
        /** 本柜需备商品（按需备数量降序） */
        List<MerchantPrepItemDto> items
) {}
