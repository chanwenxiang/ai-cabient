package com.aicabinet.trade.util;

import java.security.SecureRandom;

/**
 * 面向用户展示的业务单号：纯数字（时间毫秒 + 随机），避免 O/S/R + 十六进制字母。
 */
public final class BizIds {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private BizIds() {}

    /**
     * 22 位纯数字：13 位毫秒时间戳 + 9 位随机。
     * 随机位从 6 扩到 9：单号以 DB 主键形式直插（id-type=input），同一毫秒内两单撞号会直接
     * 主键冲突（结算路径表现为会话 FAILED）。9 位随机把同毫秒碰撞概率压到 ~1e-9 量级，
     * 按每秒 1000 单估算约数十年一遇，无需引入冲突重试。
     */
    public static String nextNumeric() {
        long ts = System.currentTimeMillis();
        int suffix = SECURE_RANDOM.nextInt(900_000_000) + 100_000_000;
        return ts + String.valueOf(suffix);
    }
}
