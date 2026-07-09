package com.aicabinet.common.dto;

import java.util.Map;

/** 充值预下单响应（按 channel 返回对应支付参数） */
public record RechargePrepayResponse(
        String channel,
        String orderId,
        WxPayParams wxPay,
        AlipayPayParams alipayPay,
        Map<String, String> debugInfo
) {}
