package com.aicabinet.trade.service;

/**
 * P3-1b：提现打款生命周期的共享语义（商户/线长两侧单点化，docs/P3_EVALUATIONS_WALLET_AND_MARKETING_2026-10-01.md B 方案第一步）。
 *
 * <p>只收口「决策与文案」，不收口编排（两侧实体/DTO 不同，编排保留各自服务）。
 * 🔴 {@link #mayAutoFailOnPayingTimeout} 是 F3 修复的唯一真源：真实渠道回执丢失时自动置 FAILED
 * =「已出款 + 已解冻」双重支出，禁止——任何一侧不得绕过本判据自写 {@code "MOCK".equals(...)}。</p>
 */
public final class WithdrawPayoutPolicy {

    /** 打款卡 PAYING 的超时阈值：超过即由对账调度兜底处置（H38；真实渠道转人工，MOCK 自动失败）。 */
    public static final long PAYING_TIMEOUT_MINUTES = 60;

    private WithdrawPayoutPolicy() {
    }

    /** F3：仅 MOCK 渠道允许 PAYING 超时自动置失败；真实渠道必须转人工核对渠道单。 */
    public static boolean mayAutoFailOnPayingTimeout(String payChannel) {
        return "MOCK".equals(payChannel);
    }

    public static String payingTimeoutFailMessage(long timeoutMinutes) {
        return "PAYING 超过 " + timeoutMinutes + " 分钟未回执，自动置失败";
    }

    public static String payingTimeoutManualNote(String payChannel, long amountCents) {
        return "PAYING 超时但渠道=" + payChannel
                + "，禁止自动置失败，请人工核对渠道打款结果后处置；金额(分)=" + amountCents;
    }

    public static String payingTimeoutAutoNote(long amountCents) {
        return "PAYING 超时自动失败并解冻；金额(分)=" + amountCents;
    }

    /**
     * P3-1b 第二步：打款渠道决策单点化（mock 开关→MOCK/WECHAT 字面量）。
     * 与 {@link #mayAutoFailOnPayingTimeout} 同族——MOCK 渠道语义的唯一出口。
     */
    public static String channelFor(boolean mockEnabled) {
        return mockEnabled ? "MOCK" : "WECHAT";
    }
}
