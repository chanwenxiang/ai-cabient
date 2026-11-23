package com.aicabinet.trade.payout;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatTransferProperties;
import com.aicabinet.trade.payment.WeChatPayV3Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * MA-407 微信商家转账通道测试——生产级核心判据：
 * ① SUCCESS 才 paid；② 非终态/未知异常必须 Pending（绝不释放冻结）；③ 确定失败才 rejected；
 * ④ openid 缺失 fail-closed；⑤ 授权单号确定性派生；
 * ⑥ <b>确认收款模式</b>：WAIT_USER_CONFIRM 必须返回「待用户确认」而不是成功/失败；
 * ⑦ <b>NO_AUTH/PARAM_ERROR/SIGN_ERROR</b> 是「请求未被受理」⇒ 必须 rejected（否则单据永久卡 PAYING）。
 */
@ExtendWith(MockitoExtension.class)
class WeChatPayoutChannelTest {

    @Mock
    private WeChatPayV3Client v3Client;

    private WeChatTransferClient transferClient;
    /** 用户授权免确认模式通道（白名单产品）。 */
    private WeChatPayoutChannel authorizedChannel;
    /** 用户确认收款模式通道（默认）。 */
    private WeChatPayoutChannel confirmChannel;

    private static final WeChatPayProperties PAY = new WeChatPayProperties(
            true, "wxapp", "mch", "https://x/notify", "key123456789012345678901234567890",
            "serial", "privatekey", "", false);

    private static WeChatTransferProperties props(String mode) {
        return new WeChatTransferProperties(true, mode, null, null, null, null,
                "https://x/payout/authorization-notify", "https://x/payout/transfer-notify");
    }

    @BeforeEach
    void setUp() {
        transferClient = new WeChatTransferClient(v3Client, PAY, props("CONFIRM"));
        authorizedChannel = new WeChatPayoutChannel(PAY, props("AUTHORIZED"), transferClient);
        confirmChannel = new WeChatPayoutChannel(PAY, props("CONFIRM"), transferClient);
    }

    private PayoutChannel.PayoutCommand command(long cents, String openid) {
        return command(cents, openid, "MW0000000001");
    }

    private PayoutChannel.PayoutCommand command(long cents, String openid, String idemKey) {
        return new PayoutChannel.PayoutCommand(1L, idemKey, PayoutConstants.PAYEE_TYPE_PERSONAL,
                "产文祥", openid, null, null, null, null, null, cents, "提现 X");
    }

    // ============ 免确认模式（AUTHORIZED）============

    @Test
    void authorizedModeSuccessMapsToPaid() {
        mockResponse(200, "{\"state\":\"SUCCESS\",\"transfer_bill_no\":\"BILL-1\"}");
        var result = authorizedChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.awaitingUserConfirm()).isFalse();
        assertThat(result.channelOrderNo()).isEqualTo("BILL-1");
    }

    @Test
    void authorizedModeNonTerminalStaysPending() {
        mockResponse(200, "{\"state\":\"PROCESSING\"}");
        assertThatThrownBy(() -> authorizedChannel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void notEnoughIsRejected() {
        mockResponse(403, "{\"code\":\"NOT_ENOUGH\",\"message\":\"Insufficient\"}");
        var result = authorizedChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.awaitingUserConfirm()).isFalse();
        assertThat(result.message()).contains("NOT_ENOUGH");
    }

    @Test
    void rateLimitStaysPending() {
        mockResponse(429, "{\"code\":\"RATELIMIT_EXCEEDED\",\"message\":\"slow down\"}");
        assertThatThrownBy(() -> authorizedChannel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void systemErrorStaysPending() {
        mockResponse(500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"retry\"}");
        assertThatThrownBy(() -> authorizedChannel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void unknownCodeStaysPending() {
        mockResponse(400, "{\"code\":\"BRAND_NEW_CODE\",\"message\":\"?\"}");
        assertThatThrownBy(() -> authorizedChannel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    /**
     * 🔴 2026-11-23 实测回归：商户未开通商家转账时微信回 403 NO_AUTH。
     * 按「结果不确定」处理会让单据永久 PAYING + 冻结不释放（只能人工解），必须 rejected。
     */
    @Test
    void noAuthIsRejectedNotPending() {
        mockResponse(403, "{\"code\":\"NO_AUTH\",\"message\":\"商户无权限发起授权或权限被处罚\"}");
        var result = authorizedChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.awaitingUserConfirm()).isFalse();
        assertThat(result.message()).contains("NO_AUTH").contains("商家转账");
    }

    @Test
    void paramErrorIsRejectedNotPending() {
        mockResponse(400, "{\"code\":\"PARAM_ERROR\",\"message\":\"参数错误\"}");
        var result = authorizedChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PARAM_ERROR");
    }

    @Test
    void missingOpenidRejected() {
        var result = authorizedChannel.transfer(command(50L, "  "));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("openid");
    }

    @Test
    void largeAmountFailsClosed() {
        // ≥ ¥2000 需加密姓名，本通道不支持 ⇒ 必须拒（不能静默少传字段）
        assertThatThrownBy(() -> authorizedChannel.transfer(command(200_000L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayeeIncompatibleException.class);
    }

    @Test
    void authorizationNoIsDeterministicAndSafe() {
        String a = authorizedChannel.authorizationNoFor("openid-1");
        String b = authorizedChannel.authorizationNoFor("openid-1");
        assertThat(a).isEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9]+");
        assertThat(a.length()).isLessThanOrEqualTo(32);
        assertThat(authorizedChannel.authorizationNoFor("openid-2")).isNotEqualTo(a);
    }

    // ============ 确认收款模式（CONFIRM，默认）============

    @Test
    void confirmModeWaitUserConfirmIsNotSuccessNorFailure() {
        mockResponse(200, "{\"state\":\"WAIT_USER_CONFIRM\",\"transfer_bill_no\":\"BILL-C1\","
                + "\"package_info\":\"pkg-abc\"}");
        var result = confirmChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.awaitingUserConfirm()).isTrue();
        assertThat(result.channelOrderNo()).isEqualTo("BILL-C1");
        assertThat(result.confirmPackage()).isEqualTo("pkg-abc");
    }

    @Test
    void confirmModeImmediateSuccessMapsToPaid() {
        mockResponse(200, "{\"state\":\"SUCCESS\",\"transfer_bill_no\":\"BILL-C2\"}");
        var result = confirmChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.awaitingUserConfirm()).isFalse();
    }

    @Test
    void confirmModeTerminalFailIsRejected() {
        mockResponse(200, "{\"state\":\"FAIL\"}");
        var result = confirmChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.awaitingUserConfirm()).isFalse();
        assertThat(result.message()).contains("FAIL");
    }

    @Test
    void confirmModeNoAuthIsRejected() {
        mockResponse(403, "{\"code\":\"NO_AUTH\",\"message\":\"产品权限未开通\"}");
        var result = confirmChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.awaitingUserConfirm()).isFalse();
        assertThat(result.message()).contains("NO_AUTH");
    }

    @Test
    void modeDefaultsToConfirmWhenBlank() {
        WeChatTransferProperties blank = new WeChatTransferProperties(true, null, null, null, null,
                null, null, null);
        assertThat(blank.mode()).isEqualTo("CONFIRM");
        assertThat(blank.confirmMode()).isTrue();
    }

    // ============ 渠道单号形态（2026-11-23 复核：旧 MW:<id> 含冒号 ⇒ 真打款必 PARAM_ERROR）============

    @Test
    void illegalOutBillNoRejectedBeforeCallingWeChat() {
        // 含冒号/下划线等非字母数字，或不足 6 位 ⇒ 本地直接拒，且绝不发请求
        for (String bad : new String[]{"MW:1", "MW_0000000001", "MW1"}) {
            var result = confirmChannel.transfer(command(50L, "openid-1", bad));
            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("out_bill_no");
        }
        org.mockito.Mockito.verifyNoInteractions(v3Client);
    }

    @Test
    void newIdemKeyIsChannelSafe() {
        String key = com.aicabinet.trade.service.PayoutAccountService.newIdemKey("MW", 42L);
        assertThat(PayoutConstants.isChannelSafeBillNo(key)).isTrue();
        assertThat(key).isEqualTo("MW0000000042");
        // 小 requestId 也不能短于 6 位（官方 out_bill_no 下限）
        assertThat(
                        PayoutConstants.isChannelSafeBillNo(
                                com.aicabinet.trade.service.PayoutAccountService.newIdemKey("LW", 1L)))
                .isTrue();
    }

    // ============ ALREADY_EXISTS ⇒ 查单裁决（绝不当「没出款」释放冻结）============

    @Test
    void alreadyExistsButQuerySuccessMapsToPaid() {
        mockResponse(400, "{\"code\":\"ALREADY_EXISTS\",\"message\":\"单据已存在\"}");
        mockQuery(200, "{\"state\":\"SUCCESS\",\"transfer_bill_no\":\"BILL-A1\"}");
        var result = confirmChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.channelOrderNo()).isEqualTo("BILL-A1");
    }

    @Test
    void alreadyExistsAndQueryFailIsRejected() {
        mockResponse(400, "{\"code\":\"ALREADY_EXISTS\",\"message\":\"单据已存在\"}");
        mockQuery(200, "{\"state\":\"FAIL\",\"transfer_bill_no\":\"BILL-A2\"}");
        var result = confirmChannel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.awaitingUserConfirm()).isFalse();
        assertThat(result.message()).contains("查单裁决");
    }

    @Test
    void alreadyExistsButQueryNotFoundStaysPending() {
        mockResponse(400, "{\"code\":\"ALREADY_EXISTS\",\"message\":\"单据已存在\"}");
        mockQuery(404, "{\"code\":\"NOT_FOUND\",\"message\":\"not found\"}");
        assertThatThrownBy(() -> confirmChannel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void alreadyExistsWhileInFlightStaysPending() {
        mockResponse(400, "{\"code\":\"ALREADY_EXISTS\",\"message\":\"单据已存在\"}");
        mockQuery(200, "{\"state\":\"PROCESSING\",\"transfer_bill_no\":\"BILL-A3\"}");
        assertThatThrownBy(() -> confirmChannel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    // ============ 模式自述（供调用方建单前判断）============

    @Test
    void requiresUserConfirmationFollowsMode() {
        assertThat(confirmChannel.requiresUserConfirmation()).isTrue();
        assertThat(authorizedChannel.requiresUserConfirmation()).isFalse();
    }

    private void mockResponse(int status, String json) {
        try {
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            when(v3Client.postWithStatus(anyString(), any()))
                    .thenReturn(new WeChatPayV3Client.HttpResult(status, body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void mockQuery(int status, String json) {
        try {
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            when(v3Client.getWithStatus(anyString()))
                    .thenReturn(new WeChatPayV3Client.HttpResult(status, body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
