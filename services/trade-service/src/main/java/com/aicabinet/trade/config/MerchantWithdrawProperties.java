package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商户提现参数（部署期初值）。
 *
 * <p>🔴 <b>为什么这里不能加「向后兼容构造器」</b>：本类是 Spring Boot 3 的
 * {@code @ConfigurationProperties} record，走<b>构造器绑定</b>。一旦存在多个构造器，
 * 框架无法确定该用哪个（实测报 {@code Failed to instantiate: No default constructor found}），
 * 除非显式标注 —— 而标注会让它从「值语义」退化成「需要维护的样板」。
 *
 * <p><b>正确做法</b>：需要「不传即用默认值」时<b>改调用点</b>（测试里显式传参），
 * 而不是给配置 record 加重载。
 *
 * <p><b>运行期可调的部分</b>（限额、费率、封顶）在 {@code SystemConfig} 运营台配置里，
 * 由 {@code WithdrawPolicyResolver} 读取并**优先于本类** —— 见该类 javadoc。
 */
@ConfigurationProperties(prefix = "aicabinet.merchant-withdraw")
public record MerchantWithdrawProperties(
        boolean mockEnabled,
        long minAmountCents,
        /** 单笔上限（分）；<=0 ⇒ 不做单笔拦截（V307 补，原先只控下限+单日） */
        long maxAmountCents,
        long dailyLimitCents,
        long reviewThresholdCents,
        /** 固定手续费（分），可与 feeBps 叠加 */
        long feeCents,
        /** 手续费万分比，如 50 = 0.5% */
        long feeBps
) {
    public MerchantWithdrawProperties {
        if (minAmountCents <= 0) {
            minAmountCents = 100;
        }
        if (dailyLimitCents <= 0) {
            dailyLimitCents = 500_000;
        }
        if (reviewThresholdCents <= 0) {
            reviewThresholdCents = 50_000;
        }
        if (feeCents < 0) {
            feeCents = 0;
        }
        if (feeBps < 0) {
            feeBps = 0;
        }
    }

    /** 单笔上限是否已配置（未配置时不拦截，由运营人工把关）。 */
    public boolean hasMaxAmount() {
        return maxAmountCents > 0;
    }
}
