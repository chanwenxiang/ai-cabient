package com.aicabinet.common.dto;

/**
 * CB-051：开门柜按商品管理库存——SKU 级 upsert 请求。
 * 全字段可选：只更新显式传入的字段；新建行时缺省 quantity=0 / capacity=0 / lowThreshold=2。
 */
public record UpsertDeviceSkuInventoryRequest(
        String skuId,
        Integer quantity,
        Integer capacity,
        Integer lowThreshold) {

    /** 简易校验：skuId 必带，数值字段不允许负数。 */
    public void validate() {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId 必填");
        }
        if ((quantity != null && quantity < 0) || (capacity != null && capacity < 0)
                || (lowThreshold != null && lowThreshold < 0)) {
            throw new IllegalArgumentException("库存/容量/阈值不允许负数");
        }
    }
}
