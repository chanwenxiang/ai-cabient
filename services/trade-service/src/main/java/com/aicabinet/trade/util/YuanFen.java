package com.aicabinet.trade.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 元 / 分换算的单一入口。
 *
 * <p>将邑文档 §3.3：「除特别注明"单位元"的字段外，金额一律以分为单位的整数」。第七章的
 * {@code salePrice}/{@code purchasePrice}（§7.6、§7.11）与 §5.4.7 的 {@code salePrice}
 * 注明单位为元，而 §5.5.2、§5.2.9、§5.4.2、§5.4.3 的同名字段单位是分——同一字段名两种单位，
 * 换算只允许在调用边界经本类完成，禁止在业务层手写 ×100/÷100。</p>
 */
public final class YuanFen {

    private YuanFen() {}

    /** 分 → 元（保留两位小数，如 2500 → 25.00）。 */
    public static BigDecimal toYuan(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /** 元 → 分；小数位超过两位视为契约错误直接拒绝，不做静默四舍五入。 */
    public static long toCents(BigDecimal yuan) {
        if (yuan == null) {
            throw new IllegalArgumentException("金额为空");
        }
        BigDecimal scaled = yuan.stripTrailingZeros();
        if (scaled.scale() > 2) {
            throw new IllegalArgumentException("金额精度超过两位小数: " + yuan.toPlainString());
        }
        return yuan.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
}
