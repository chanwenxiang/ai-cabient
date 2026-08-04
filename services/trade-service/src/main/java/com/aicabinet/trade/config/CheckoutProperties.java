package com.aicabinet.trade.config;

import com.aicabinet.common.constants.CabinetConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.checkout")
public record CheckoutProperties(boolean balanceOnly, Integer preauthCents) {

    /** 开门预授权默认额度（分）；未配置或非法时回退 {@link CabinetConstants#MIN_BALANCE_CENTS}。 */
    public int resolvePreauthCents() {
        if (preauthCents == null || preauthCents <= 0) {
            return CabinetConstants.MIN_BALANCE_CENTS;
        }
        return preauthCents;
    }
}
