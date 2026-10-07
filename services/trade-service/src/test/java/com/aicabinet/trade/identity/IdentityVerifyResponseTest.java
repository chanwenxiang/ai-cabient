package com.aicabinet.trade.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三方实名核验响应的判定规则。
 *
 * <p>🔴 <b>这是安全关键判据，且此前零测试覆盖</b>。
 * 它决定「谁被标记为已实名」⇒判据过宽= 未实名用户被放行。
 */
class IdentityVerifyResponseTest {

    // ── 明确通过 ────────────────────────────────────

    @Test
    @DisplayName("passed=true ⇒ 通过")
    void passedTrue_isOk() {
        assertTrue(new IdentityVerifyClient.IdentityVerifyResponse(null, null, true).isOk());
    }

    @Test
    @DisplayName("matched=true ⇒ 通过")
    void matchedTrue_isOk() {
        assertTrue(new IdentityVerifyClient.IdentityVerifyResponse(true, null, null).isOk());
    }

    // ── 🔴 核心：success 不再算「核验通过」 ──────────

    @Test
    @DisplayName("🔴 success=true 但 passed/matched 缺失 ⇒ **不通过**（旧实现会误判为通过）")
    void successAlone_isRejected() {
        // 第三方「请求处理成功」≠「核验通过」。若这里放行，
        // 任何姓名+后4位都能拿到 verified=true ⇒ 未实名即可提现/开门。
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(null, true, null).isOk());
    }

    @Test
    @DisplayName("🔴 success=true 且 passed=false ⇒ **不通过**（这是最危险的组合）")
    void successTruePassedFalse_isRejected() {
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(null, true, false).isOk());
    }

    @Test
    @DisplayName("🔴 success=true 且 matched=false ⇒ **不通过**")
    void successTrueMatchedFalse_isRejected() {
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(false, true, null).isOk());
    }

    // ── 明确不通过 ──────────────────────────────────

    @Test
    @DisplayName("全false ⇒ 不通过")
    void allFalse_isRejected() {
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(false, false, false).isOk());
    }

    @Test
    @DisplayName("🔴 全部字段为 null ⇒ **不通过**（不猜「没报错就是过」）")
    void allNull_isRejected() {
        // 契约变更/第三方返回结构变化时，宁可拒（用户重试）不可放。
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(null, null, null).isOk());
    }

    @Test
    @DisplayName("只有 passed 显式 false而 success=true ⇒ 不通过")
    void passedFalseOthersTrue_isRejected() {
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(true, true, false).isOk());
    }

    // ── describe()：供排障区分字段缺失 vs 明确不通过 ──

    @Test
    @DisplayName("describe() 列出全部字段（不泄露 PII）")
    void describe_listsAllFields() {
        String s = new IdentityVerifyClient.IdentityVerifyResponse(false, true, null).describe();
        assertTrue(s.contains("passed=null"), s);
        assertTrue(s.contains("matched=false"), s);
        assertTrue(s.contains("success=true"), s);
    }

    @Test
    @DisplayName("🔴 describe() 不含姓名/证件号（PII 禁止进日志）")
    void describe_hasNoPii() {
        String s = new IdentityVerifyClient.IdentityVerifyResponse(true, true, true).describe();
        assertFalse(s.contains("realName"), s);
        assertFalse(s.contains("idCard"), s);
    }
}
