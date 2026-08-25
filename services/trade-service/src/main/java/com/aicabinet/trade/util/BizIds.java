package com.aicabinet.trade.util;

import java.security.SecureRandom;

/**
 * 面向用户展示的业务单号：纯数字（时间毫秒 + 随机），避免 O/S/R + 十六进制字母。
 */
public final class BizIds {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private BizIds() {}

    /** 19 位左右纯数字：13 位毫秒时间戳 + 6 位随机 */
    public static String nextNumeric() {
        long ts = System.currentTimeMillis();
        int suffix = SECURE_RANDOM.nextInt(900_000) + 100_000;
        return ts + String.valueOf(suffix);
    }
}
