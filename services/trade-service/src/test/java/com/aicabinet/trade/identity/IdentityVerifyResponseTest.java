package com.aicabinet.trade.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vendor（阿里云市场「身份证二要素」）响应的判定规则。
 *
 * <p>🔴 <b>这是安全关键判据</b>：它决定「谁被标记为已实名」⇒
 * 判据过宽 = 未实名用户被放行（提现资金出口失守）。
 *
 * <p>vendor 契约（2026-10-20 官方文档取证）：
 * 只有 {@code result_code=="0000"} 且 {@code result.checkresult==1} 才算核验通过。
 */
class IdentityVerifyResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private IdentityVerifyClient.IdentityVerifyResponse parse(String json) throws Exception {
        return mapper.readValue(json, IdentityVerifyClient.IdentityVerifyResponse.class);
    }

    // ── 🔴 唯一通过路径 ─────────────────────────────

    @Test
    @DisplayName("result_code=0000 且 checkresult=1 ⇒ 通过")
    void matched_isOk() {
        var r = new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功",
                new IdentityVerifyClient.IdentityVerifyResponse.Result(1, "身份证号和姓名一致"));
        assertTrue(r.isOk());
    }

    @Test
    @DisplayName("🔴 真实 JSON 样例（snake_case）绑定后 ⇒ 通过（绑定字段名错误会假阴/假阳）")
    void realJsonBinding() throws Exception {
        var r = parse("""
                {"result_code":"0000","result_msg":"调用成功","charge":1,
                 "result":{"checkresult":1,"message":"身份证号和姓名一致"},
                 "request_id":"TIDdf30d192d17f403b815d40654dbde810"}
                """);
        assertEquals("0000", r.resultCode());
        assertTrue(r.isOk());
    }

    // ── 🔴 明确不通过 ───────────────────────────────

    @Test
    @DisplayName("checkresult=2（不一致）⇒ 不通过")
    void mismatched_isRejected() throws Exception {
        var r = parse("""
                {"result_code":"0000","result_msg":"调用成功",
                 "result":{"checkresult":2,"message":"身份证号和姓名不一致"}}
                """);
        assertFalse(r.isOk());
    }

    @Test
    @DisplayName("checkresult=3（库无）⇒ 不通过（户籍迁移/军人等，引导文案在错误映射层）")
    void noRecord_isRejected() throws Exception {
        var r = parse("""
                {"result_code":"0000","result_msg":"调用成功",
                 "result":{"checkresult":3,"message":"库中无此证"}}
                """);
        assertFalse(r.isOk());
    }

    @Test
    @DisplayName("checkresult=110（同证号当日超限）⇒ 不通过（限流不是通过）")
    void rateLimited_isRejected() {
        var r = new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功",
                new IdentityVerifyClient.IdentityVerifyResponse.Result(110, "请求次数过多"));
        assertFalse(r.isOk());
    }

    // ── 🔴 调用层失败/字段缺失 ⇒ fail-closed ────────

    @Test
    @DisplayName("🔴 result_code!=0000（如 2102 参数错误）⇒ 不通过（调用失败≠核验通过）")
    void vendorFailureCode_isRejected() {
        var r = new IdentityVerifyClient.IdentityVerifyResponse("2102", "请求参数错误", null);
        assertFalse(r.isOk());
    }

    @Test
    @DisplayName("🔴 result_code 缺失 ⇒ 不通过（不猜「没报错就是过」）")
    void missingResultCode_isRejected() {
        var r = new IdentityVerifyClient.IdentityVerifyResponse(null, null,
                new IdentityVerifyClient.IdentityVerifyResponse.Result(1, "身份证号和姓名一致"));
        assertFalse(r.isOk());
    }

    @Test
    @DisplayName("🔴 result 缺失 ⇒ 不通过")
    void missingResult_isRejected() {
        var r = new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功", null);
        assertFalse(r.isOk());
    }

    @Test
    @DisplayName("🔴 checkresult 缺失/未知值 ⇒ 不通过（契约变更时 fail-closed）")
    void unknownCheckResult_isRejected() {
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功",
                new IdentityVerifyClient.IdentityVerifyResponse.Result(null, "x")).isOk());
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功",
                new IdentityVerifyClient.IdentityVerifyResponse.Result(99, "x")).isOk());
    }

    @Test
    @DisplayName("🔴 全部字段为 null ⇒ 不通过")
    void allNull_isRejected() {
        assertFalse(new IdentityVerifyClient.IdentityVerifyResponse(null, null, null).isOk());
    }

    // ── describe()：排障用，不含 PII ────────────────

    @Test
    @DisplayName("describe() 列出判定字段（不泄露 PII）")
    void describe_listsFields() {
        String s = new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功",
                new IdentityVerifyClient.IdentityVerifyResponse.Result(2, "不一致")).describe();
        assertTrue(s.contains("resultCode=0000"), s);
        assertTrue(s.contains("checkresult=2"), s);
    }

    @Test
    @DisplayName("🔴 describe() 不含姓名/证件号（PII 禁止进日志）")
    void describe_hasNoPii() {
        String s = new IdentityVerifyClient.IdentityVerifyResponse("0000", "调用成功",
                new IdentityVerifyClient.IdentityVerifyResponse.Result(1, "一致")).describe();
        assertFalse(s.contains("name="), s);
        assertFalse(s.contains("id"), s);
    }
}
