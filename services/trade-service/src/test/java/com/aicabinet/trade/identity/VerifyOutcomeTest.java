package com.aicabinet.trade.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V322：核验结果的「可信度」标记。
 *
 * <p>🔴 <b>这条判据是安全关键</b>：mock 环境下的「通过」<b>不是</b>真核验通过。
 * 若调用方把它当真的⇒ 开发/测试环境里
 * <b>输入任意姓名 + 4 位数字即实名成功</b> ⇒ 可开通免密支付 ⇒ 🔴 白嫖。
 */
class VerifyOutcomeTest {

    @Test
    @DisplayName("真核验通过 ⇒ trusted")
    void realPass_isTrusted() {
        assertTrue(new IdentityVerifyClient.VerifyOutcome(true, false).trusted());
    }

    @Test
    @DisplayName("🔴 mock 通过 ⇒ verified=true 但**trusted=false**（这正是要防的那个洞）")
    void mockPass_isNotTrusted() {
        var outcome = new IdentityVerifyClient.VerifyOutcome(true, true);
        // verified 为 true（它确实返回「通过」）
        assertTrue(outcome.verified());
        // 🔴 但不可信 —— 调用方必须据此拒绝 setVerified(true)
        assertFalse(outcome.trusted());
    }

    @Test
    @DisplayName("核验不通过 ⇒ 两种情况都不可信")
    void notPass_isNotTrusted() {
        assertFalse(new IdentityVerifyClient.VerifyOutcome(false, false).trusted());
        assertFalse(new IdentityVerifyClient.VerifyOutcome(false, true).trusted());
    }
}
