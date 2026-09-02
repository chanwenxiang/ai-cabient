package com.aicabinet.trade.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 提现手续费：固定分 + 万分比，须严格小于提现金额，到账 = 提现额 − 手续费。
 */
public final class WithdrawFeeCalculator {

    private WithdrawFeeCalculator() {}

    public static long computeFeeCents(long amountCents, long flatFeeCents, long feeBps) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额必须大于 0");
        }
        long flat = Math.max(0L, flatFeeCents);
        long bps = Math.max(0L, feeBps);
        long fee = flat + amountCents * bps / 10_000L;
        if (fee >= amountCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现手续费不能大于或等于提现金额");
        }
        return fee;
    }

    public static long netPayoutCents(long amountCents, Long feeCents) {
        long fee = feeCents == null ? 0L : Math.max(0L, feeCents);
        return Math.max(0L, amountCents - fee);
    }
}
