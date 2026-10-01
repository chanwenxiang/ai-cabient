package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-1b：F3 判据单点化后的钉子——真实渠道绝不允许 PAYING 超时自动置失败。
 */
class WithdrawPayoutPolicyTest {

    @Test
    void onlyMockChannelMayAutoFailOnPayingTimeout() {
        assertTrue(WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout("MOCK"));
        assertFalse(WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout("WECHAT"));
        assertFalse(WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout(null));
        assertFalse(WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout(""));
    }

    @Test
    void messagesCarryTimeoutAndAmount() {
        assertEquals("PAYING 超过 60 分钟未回执，自动置失败",
                WithdrawPayoutPolicy.payingTimeoutFailMessage(60));
        assertTrue(WithdrawPayoutPolicy.payingTimeoutManualNote("WECHAT", 500)
                .contains("禁止自动置失败"));
        assertTrue(WithdrawPayoutPolicy.payingTimeoutAutoNote(500).contains("解冻"));
    }
}
