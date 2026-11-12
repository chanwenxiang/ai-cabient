package com.aicabinet.trade.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运营侧单笔资金变动的硬上限（R19-6，2026-11-12）。
 *
 * <p>此前三处人工资金入口对金额零限幅：消费者余额调账收 {@code int}（可写满 21,474,836.47 元）、
 * 线长钱包调账与平台资金池调账收 {@code long}（可写 9e18 再提现）。限幅是**自己的设计判断**
 * （沿用 {@code WalletAdjustRequest} 已上线的 ±100 万元口径），不做产品配置项——
 * 配置项意味着误配一次就重新打开缺口；确有大额需求走多笔或审批流。
 *
 * <p>注意不能用 {@code Math.abs} 判界：{@code Math.abs(Long.MIN_VALUE)} 仍是负数，
 * 负数金额会绕过上限检查（这正是 R19-6 想堵的那类反转）。
 */
public final class MoneyLimits {

    /** 运营对「用户/线长」账户的单笔调账上限：±100 万元（与 WalletAdjustRequest 同口径）。 */
    public static final long MAX_ACCOUNT_ADJUST_CENTS = 100_000_000L;

    /** 平台资金池（公司级账户）的单笔调账/归集上限：1000 万元。 */
    public static final long MAX_PLATFORM_FLOW_CENTS = 1_000_000_000L;

    private MoneyLimits() {}

    /** 带符号金额限幅（调账类：可正可负）。越界抛 400，文案带上限与实收值。 */
    public static void requireWithin(String label, long cents, long limitCents) {
        if (cents > limitCents || cents < -limitCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + "超出单笔上限（±" + (limitCents / 100.0) + " 元），当前 " + (cents / 100.0) + " 元");
        }
    }

    /** 无符号金额限幅（归集类：只允许正数，另行判定 0/负）。 */
    public static void requirePositiveWithin(String label, long cents, long limitCents) {
        if (cents <= 0 || cents > limitCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + "必须在 0～" + (limitCents / 100.0) + " 元之间，当前 " + (cents / 100.0) + " 元");
        }
    }
}
