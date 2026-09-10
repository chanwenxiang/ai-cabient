package com.aicabinet.common.dto;

/**
 * 商户提交补货申请的行项目。
 * 独立顶层类型，避免与 {@link CreateWarehouseTransferRequest.Line} 在 springdoc 中同名冲突。
 */
public record CreateMerchantReplenishmentRequestLine(String skuId, Integer requestedQty) {}
