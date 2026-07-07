package com.aicabinet.common.dto;

import java.util.Map;

/** 微信小程序 wx.requestPayment 所需参数 */
public record WxPayParams(
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign,
        Map<String, String> debugInfo
) {}
