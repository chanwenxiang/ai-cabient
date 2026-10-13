package com.aicabinet.common.dto;

/**
 * 绑定外部商品映射请求（CB-036）。
 *
 * @param skuId 本系统 SKU（必填，须存在于 SKU 目录）
 * @param note  备注（可空）
 */
public record ExternalMappingBindRequest(String skuId, String note) {
}
