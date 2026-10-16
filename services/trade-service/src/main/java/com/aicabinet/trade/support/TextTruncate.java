package com.aicabinet.trade.support;

/**
 * A7/P3：按 UTF-16 code unit 的 {@code substring(0, max)} 会把代理对（emoji 等
 * BMP 外字符）从中间切断，落库成非法孤代理（展示乱码/部分 DB 报编码错）。
 * 按边界回退：max 落在代理对高位上则少截一位，保证不产出半个字符。
 */
public final class TextTruncate {

    private TextTruncate() {}

    public static String safeTruncate(String value, int maxCodeUnits) {
        if (value == null || value.length() <= maxCodeUnits) {
            return value;
        }
        if (maxCodeUnits < 1) {
            return "";
        }
        int cut = maxCodeUnits;
        if (Character.isHighSurrogate(value.charAt(maxCodeUnits - 1))
                && maxCodeUnits < value.length()
                && Character.isLowSurrogate(value.charAt(maxCodeUnits))) {
            cut = maxCodeUnits - 1;
        }
        return value.substring(0, cut);
    }
}
