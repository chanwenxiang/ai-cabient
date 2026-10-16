package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MerchantWithdrawProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商户提现手续费档位（2026-10-16 拍板）：全局默认 <b>60 万分比（6‰，老系统标准档）</b>、
 * 封顶 0=不封顶（单笔/单日上限已是天花板），个别商户走
 * {@code merchant.withdraw.fee_bps.{商户号}} 覆盖 —— 覆盖值非法时<b>必须</b>回退全局，
 * 不能因为一次误填把该商户变成免手续费。
 */
class WithdrawFeePolicyTest {

    /** yml 只当「运营台没配」时的兜底；测试里刻意传 feeBps=0 以便区分两层的取值。 */
    private final MerchantWithdrawProperties yml =
            new MerchantWithdrawProperties(true, 1000L, 1_000_000L, 4_000_000L, 50_000L, 0L, 0L);

    private WithdrawPolicyResolver withConfig(String globalBps, String overrideBps) {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getValue(eq(SystemConfigService.MERCHANT_WITHDRAW_FEE_BPS), isNull()))
                .thenReturn(globalBps);
        when(config.getValue(eq(SystemConfigService.MERCHANT_WITHDRAW_FEE_CAP_CENTS), isNull()))
                .thenReturn("0");
        when(config.getValue(eq("merchant.withdraw.fee_bps.M1"), isNull()))
                .thenReturn(overrideBps);
        return new WithdrawPolicyResolver(config, yml, null);
    }

    @Test
    void globalSeedIsSixPermille() {
        assertEquals(60L, withConfig("60", null).merchantFeeBpsFor("M1"),
                "全局档=60 万分比（6‰）——种子/yml/V381 三处必须同值");
    }

    @Test
    void merchantOverrideWinsOverGlobal() {
        // 合同谈好 4‰ 的商户：只改自己那一行，别人不受影响
        assertEquals(40L, withConfig("60", "40").merchantFeeBpsFor("M1"));
        assertEquals(60L, withConfig("60", "40").merchantFeeBpsFor("M2"),
                "覆盖只对目标商户生效");
    }

    @Test
    void zeroOverrideMeansThatMerchantIsFeeExempt() {
        // 0 是「该商户免收」的合法值，不能被当成「没配」而回退全局 60
        assertEquals(0L, withConfig("60", "0").merchantFeeBpsFor("M1"));
    }

    @Test
    void illegalOverrideFallsBackToGlobal() {
        assertEquals(60L, withConfig("60", "abc").merchantFeeBpsFor("M1"), "非数字必须回退全局");
        assertEquals(60L, withConfig("60", "-1").merchantFeeBpsFor("M1"), "负数必须回退全局");
        assertEquals(60L, withConfig("60", "  ").merchantFeeBpsFor("M1"), "空串=未配置");
        assertEquals(60L, withConfig("60", null).merchantFeeBpsFor(null), "商户号为空走全局");
    }

    @Test
    void noCapSoFeeAtSingleUpperLimitIsSixYuan() {
        // 封顶留 0 的依据：单笔上限 ¥10000（1_000_000 分）上 6‰ 最多 ¥60，
        // 配 ¥25/¥50 封顶几乎永不触发=死参数。
        long fee = WithdrawFeeCalculator.computeFeeCents(1_000_000L, 0L, 60L, 0L);
        assertEquals(6_000L, fee);
        assertEquals(994_000L, WithdrawFeeCalculator.netPayoutCents(1_000_000L, fee));
    }
}
