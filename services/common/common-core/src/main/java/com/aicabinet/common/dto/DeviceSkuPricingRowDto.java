package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * admin 设备详情页「单柜定价」行（V350，CB-037）。
 * 一行 = 该柜内一个 SKU 的定价状态；行内编辑（不弹窗）由前端消费。
 */
public record DeviceSkuPricingRowDto(
        String deviceId,
        String deviceName,
        String skuId,
        String skuName,
        /** 商品库基准价（全局）。 */
        int basePriceCents,
        /** 该柜覆盖价；null=未设覆盖（跟随基准）。 */
        Integer overridePriceCents,
        /** 覆盖价是否启用（CB-037 语义 C：false=预设价暂不生效，取价回落基准）。 */
        boolean overrideEnabled,
        /** 当前实际成交基准：覆盖价（且启用）否则基准价（不含时段/清仓折扣，折扣下单时叠加）。 */
        int effectivePriceCents,
        /** 最低可设价（分）：SKU 显式 min_price_cents，null 规则=1（仅要求 >0，不做成本硬线）。 */
        int minPriceCents,
        /** 最高可设价（分）：SKU 显式 max_price_cents，缺省=基准价 2 倍。 */
        int maxPriceCents,
        int sellableQty,
        String imageUrl,
        /** 进货成本（可空）：前端据此在覆盖价低于成本时做非阻断提醒（CB-037：打折放行、知情即可）。 */
        Integer purchaseCostCents,
        /** 乐观锁版本（覆盖价行的 version；未设覆盖=0）。 */
        long version,
        Instant overrideUpdatedAt
) {}
