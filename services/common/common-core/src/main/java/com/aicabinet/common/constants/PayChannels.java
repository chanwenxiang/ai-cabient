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

    /** 仅接受 WECHAT / ALIPAY；其他返回 null。 */
    public static String normalizeEntryChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String c = channel.trim().toUpperCase();
        if (WECHAT.equals(c) || ALIPAY.equals(c)) {
            return c;
        }
        return null;
    }
}
