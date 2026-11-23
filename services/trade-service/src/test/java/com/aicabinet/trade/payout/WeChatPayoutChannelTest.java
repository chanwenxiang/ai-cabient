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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * MA-407 微信商家转账通道测试——生产级核心判据：
 * ① SUCCESS 才 paid；② 非终态/未知异常必须 Pending（绝不释放冻结）；③ 确定失败才 rejected；
 * ④ openid 缺失 fail-closed；⑤ 授权单号确定性派生。
 */
@ExtendWith(MockitoExtension.class)
class WeChatPayoutChannelTest {

    @Mock
    private WeChatPayV3Client v3Client;

    private WeChatTransferClient transferClient;
    private WeChatPayoutChannel channel;

    private static final WeChatPayProperties PAY = new WeChatPayProperties(
            true, "wxapp", "mch", "https://x/notify", "key123456789012345678901234567890",
            "serial", "privatekey", "", false);
    private static final WeChatTransferProperties TRANSFER = new WeChatTransferProperties(
            true, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        transferClient = new WeChatTransferClient(v3Client, PAY, TRANSFER);
        channel = new WeChatPayoutChannel(PAY, TRANSFER, transferClient);
    }

    private PayoutChannel.PayoutCommand command(long cents, String openid) {
        return new PayoutChannel.PayoutCommand(1L, "MW-idem-1", PayoutConstants.PAYEE_TYPE_PERSONAL,
                "产文祥", openid, null, null, null, null, null, cents, "提现 X");
    }

    @Test
    void successMapsToPaid() {
        mockTransferResponse(200, "{\"state\":\"SUCCESS\",\"transfer_bill_no\":\"BILL-1\"}");
        var result = channel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.channelOrderNo()).isEqualTo("BILL-1");
    }

    @Test
    void nonTerminalStateStaysPending() {
        mockTransferResponse(200, "{\"state\":\"PROCESSING\"}");
        assertThatThrownBy(() -> channel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void notEnoughIsRejected() {
        mockTransferResponse(403, "{\"code\":\"NOT_ENOUGH\",\"message\":\"Insufficient\"}");
        var result = channel.transfer(command(50L, "openid-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("NOT_ENOUGH");
    }

    @Test
    void rateLimitStaysPending() {
        mockTransferResponse(429, "{\"code\":\"RATELIMIT_EXCEEDED\",\"message\":\"slow down\"}");
        assertThatThrownBy(() -> channel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void systemErrorStaysPending() {
        mockTransferResponse(500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"retry\"}");
        assertThatThrownBy(() -> channel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void unknownCodeStaysPending() {
        mockTransferResponse(400, "{\"code\":\"BRAND_NEW_CODE\",\"message\":\"?\"}");
        assertThatThrownBy(() -> channel.transfer(command(50L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayoutPendingException.class);
    }

    @Test
    void missingOpenidRejected() {
        var result = channel.transfer(command(50L, "  "));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("openid");
    }

    @Test
    void largeAmountFailsClosed() {
        // ≥ ¥2000 需加密姓名，本通道不支持 ⇒ 必须拒（不能静默少传字段）
        assertThatThrownBy(() -> channel.transfer(command(200_000L, "openid-1")))
                .isInstanceOf(PayoutChannel.PayeeIncompatibleException.class);
    }

    @Test
    void authorizationNoIsDeterministicAndSafe() {
        String a = channel.authorizationNoFor("openid-1");
        String b = channel.authorizationNoFor("openid-1");
        assertThat(a).isEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9]+");
        assertThat(a.length()).isLessThanOrEqualTo(32);
        assertThat(channel.authorizationNoFor("openid-2")).isNotEqualTo(a);
    }

    private void mockTransferResponse(int status, String json) {
        try {
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            when(v3Client.postWithStatus(anyString(), any()))
                    .thenReturn(new WeChatPayV3Client.HttpResult(status, body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
