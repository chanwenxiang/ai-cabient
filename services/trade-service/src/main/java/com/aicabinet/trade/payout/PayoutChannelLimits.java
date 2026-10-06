package com.aicabinet.trade.payout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 打款通道的<b>渠道侧硬限额</b>（厂商规则，代码常量，非运营配置）。
 *
 * <p>🔴 <b>为什么要区分「渠道硬限额」与「运营限额」</b>：
 * 微信/支付宝的额度是<b>商户号等级决定</b>的，代码改不动也不该改 ——
 * 它是渠道会<b>硬拒</b>的边界。而运营限额（本项目 {@code merchant.withdraw.*}）是我们
 * 自己的风控偏好。两者的正确关系是<b>取更严的那个</b>：
 * <pre>
 *   实际可提 = min(渠道硬限额, 运营限额)
 * </pre>
 * 若让运营配置去覆盖渠道限额，运营一旦填了 ¥10000 单笔，渠道会以
 * 「单笔超过限额」把请求<b>全部拒掉</b> ⇒ 商户看到的是「申请成功但一直没到账」，
 * 远难于「当场提示单笔不能超过 ¥200」。所以 {@link #effective} 只允许<b>收紧</b>。
 *
 * <p><b>0 一律表示不限制</b>（与全系统 {@code RECHARGE_MAX_CENTS} 口径一致）。
 *
 * <p>三个维度的语义（微信「商家转账到零钱」默认档）：
 * <ul>
 *   <li>{@code singleCents} —— <b>单笔</b>上限，默认 ¥200；</li>
 *   <li>{@code perPayeeDailyCents} —— <b>单个收款人单日</b>累计，默认 ¥2000；</li>
 *   <li>{@code dailyTotalCents} —— <b>本商户号单日总额</b>，默认 ¥50000。
 *       这是<b>整个商户号共用</b>的池子，不是每商户各一份。</li>
 * </ul>
 */
public record PayoutChannelLimits(
        long singleCents,
        long perPayeeDailyCents,
        long dailyTotalCents
) {

    /** 无任何渠道限额（银行代付 / 未接渠道的默认形态）。 */
    public static final PayoutChannelLimits UNLIMITED = new PayoutChannelLimits(0L, 0L, 0L);

    public PayoutChannelLimits {
        if (singleCents < 0L || perPayeeDailyCents < 0L || dailyTotalCents < 0L) {
            throw new IllegalArgumentException("渠道限额不可为负：single=" + singleCents
                    + ", perPayeeDaily=" + perPayeeDailyCents + ", dailyTotal=" + dailyTotalCents);
        }
    }

    public boolean hasSingleLimit() {
        return singleCents > 0L;
    }

    public boolean hasPerPayeeDailyLimit() {
        return perPayeeDailyCents > 0L;
    }

    public boolean hasDailyTotalLimit() {
        return dailyTotalCents > 0L;
    }

    /**
     * 与运营限额求交集：<b>逐维取更严者</b>，任一为 0（不限制）时取另一个。
     *
     * @param ops 运营侧限额，允许全为 0（不限制）
     */
    public PayoutChannelLimits effective(PayoutChannelLimits ops) {
        if (ops == null) {
            return this;
        }
        return new PayoutChannelLimits(
                stricter(singleCents, ops.singleCents),
                stricter(perPayeeDailyCents, ops.perPayeeDailyCents),
                stricter(dailyTotalCents, ops.dailyTotalCents));
    }

    private static long stricter(long channelSide, long opsSide) {
        if (channelSide <= 0L) {
            return Math.max(0L, opsSide);
        }
        if (opsSide <= 0L) {
            return channelSide;
        }
        return Math.min(channelSide, opsSide);
    }

    /** 运营台「打款模式」面板展示用（顺序稳定，前端按此顺序渲染）。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("singleCents", singleCents);
        m.put("perPayeeDailyCents", perPayeeDailyCents);
        m.put("dailyTotalCents", dailyTotalCents);
        return m;
    }

    @Override
    public String toString() {
        return "PayoutChannelLimits{single=" + singleCents
                + ", perPayeeDaily=" + perPayeeDailyCents
                + ", dailyTotal=" + dailyTotalCents + "}";
    }
}