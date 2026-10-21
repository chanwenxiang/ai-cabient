package com.aicabinet.common.dto;

public record DeviceProductDto(
        String skuId,
        String skuName,
        /** 规格（如「330ml」「550ml*12」），与 admin 商品档案同源；可为空。 */
        String spec,
        /** 计价单位（如「瓶」「罐」「件」），价目展示为「¥3.50/瓶」；null 时前端按「件」兜底。 */
        String unit,
        int priceCents,
        int quantity,
        String imageUrl,
        String category,
        String description
) {}
