package com.aicabinet.common.constants;

public final class PayChannels {

    public static final String WECHAT = "WECHAT";
    public static final String ALIPAY = "ALIPAY";
    public static final String BALANCE = "BALANCE";

    private PayChannels() {
    }

    public static String normalize(String channel) {
        if (channel == null || channel.isBlank()) {
            return WECHAT;
        }
        return channel.trim().toUpperCase();
    }
}
