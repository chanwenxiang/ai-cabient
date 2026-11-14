package com.aicabinet.trade.config;

import com.aicabinet.common.constants.CabinetConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.checkout")
public record CheckoutProperties(boolean balanceOnly, Integer preauthCents) {

    /**
     * yml 静态兜底额度（分）：未配置或非法（null / ≤0）时回退 {@link CabinetConstants#MIN_BALANCE_CENTS}。
     *
     * <p>⚠️ 与 DB 动态配置 {@code checkout.preauth_cents} 的 <b>0 语义不同</b>：DB 配 0 是
     * 「关闭预授权」的合法业务值（见 {@code ConsumerPreauthService.resolvePreauthCents} 的显式
     * 0 分支）；yml 这里只表示「没配好」，没有关闭语义。两者都写了 0，勿把其中一处的处理
     * 直接复制到另一处 —— 这正是 9d5c724f 把 0 静默回落的成因。
     */
    public int resolvePreauthCents() {
        if (preauthCents == null || preauthCents <= 0) {
            return CabinetConstants.MIN_BALANCE_CENTS;
        }
        return preauthCents;
    }
}
