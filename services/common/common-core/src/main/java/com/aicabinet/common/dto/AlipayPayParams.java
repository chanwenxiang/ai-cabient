package com.aicabinet.common.dto;

/** 支付宝 WAP/小程序支付参数 */
public record AlipayPayParams(
        String orderId,
        String tradeNo,
        String payUrl,
        String payFormHtml
) {}
