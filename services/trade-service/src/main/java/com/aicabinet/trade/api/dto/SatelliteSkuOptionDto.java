package com.aicabinet.trade.api.dto;

/** 补货员采购入库可选商品（件数下单；单价取目录采购价）。 */
public record SatelliteSkuOptionDto(String skuId, String skuName, int unitCostCents) {}
