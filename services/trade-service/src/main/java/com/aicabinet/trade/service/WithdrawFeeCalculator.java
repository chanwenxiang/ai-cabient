package com.aicabinet.trade.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 提现手续费：固定分 + 万分比（可叠加），<b>再受单笔封顶约束</b>；到账 = 提现额 − 手续费。
 *
 * <p><b>为什么必须有封顶</b>：业界通行做法都对手续费设「单笔最高」——
 * 支付宝余额提现到银行卡 0.1%、<b>最低 ¥1、最高 ¥25</b>；拉卡拉提现 0.1%、<b>最高 ¥50</b>。
 * 若只有「固定 + 比例」而无封顶，配 {@code feeBps=50}（0.5%）时：
 * 提现 ¥10000 扣 ¥50、提现 ¥100000 扣 ¥500 —— 后者是大商户的合理提现额，
 * 却被扣走 0.5%，**必然引发投诉**。封顶是行业惯例，也是我们与竞品的对齐项。
 *
 * <p><b>封顶语义</b>：{@code feeCapCents <= 0} 表示不封顶（保持接入前行为，零行为变化）。
 */
public final class WithdrawFeeCalculator {

    private WithdrawFeeCalculator() {}

    /**
     * 计算手续费（分）。
     *
     * @param amountCents 提现金额（分），必须 > 0
     * @param flatFeeCents 固定手续费（分），负数按 0
     * @param feeBps       万分比（如 50 = 0.5%），负数按 0
     * @param feeCapCents  单笔封顶（分）；<= 0 表示<b>不封顶</b>
     * @throws ResponseStatusException 金额非法，或手续费 ≥ 提现金额（此时到账为 0 或负）
     */
    public static long computeFeeCents(long amountCents, long flatFeeCents, long feeBps, long feeCapCents) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额必须大于 0");
        }
        long flat = Math.max(0L, flatFeeCents);
        long bps = Math.max(0L, feeBps);
        long fee = flat + amountCents * bps / 10_000L;
        // 🔴 溢出保护：amountCents 已是 long，bps 异常大时乘法可能溢出为负
        if (fee < 0L) {
            fee = Long.MAX_VALUE;
        }
        long cap = Math.max(0L, feeCapCents);
        if (cap > 0L) {
            fee = Math.min(fee, cap);
        }
        // 封顶只压低手续费，不会把一个本已 < 金额的手续费抬到 ≥ 金额 ⇒ 判定放在封顶之后即可。
        // 反例（必须拒）：提现 ¥10（1000）+ 固定费 ¥15（1500）> 金额 ⇒ 到账为负。
        if (fee >= amountCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现手续费不能大于或等于提现金额");
        }
        return fee;
    }

    /** 无封顶的重载（接入前语义，保持向后兼容）。 */
    public static long computeFeeCents(long amountCents, long flatFeeCents, long feeBps) {
        return computeFeeCents(amountCents, flatFeeCents, feeBps, 0L);
    }

    public static long netPayoutCents(long amountCents, Long feeCents) {
        long fee = feeCents == null ? 0L : Math.max(0L, feeCents);
        return Math.max(0L, amountCents - fee);
    }
}
