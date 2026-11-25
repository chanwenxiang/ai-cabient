package com.aicabinet.trade.api;

import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.service.PayScoreOrderNotifyService;
import com.aicabinet.trade.service.PayScoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayScoreNotifyControllerTest {

    @Mock private WeChatPayNotifyService notifyService;
    @Mock private PayScoreService payScoreService;
    @Mock private PayScoreOrderNotifyService payScoreOrderNotifyService;

    @Test
    void handlingFailure_releasesNonceForWechatRetry() throws Exception {
        PayScoreNotifyController controller = new PayScoreNotifyController(
                notifyService, payScoreService, payScoreOrderNotifyService);
        JsonNode event = new ObjectMapper().readTree("{\"authorization_code\":\"auth-1\"}");
        when(notifyService.parseAndVerify("body", "ts", "nonce-1", "sig", "serial"))
                .thenReturn(event);
        doThrow(new IllegalStateException("db down"))
                .when(payScoreService).handleWechatPermissionNotify(event);

        assertThrows(IllegalStateException.class, () -> controller.notify(
                "body", "ts", "nonce-1", "sig", "serial", null));

        verify(notifyService).releaseNonce("nonce-1");
    }

    @Test
    void parseFailure_doesNotReleaseNonceInController() {
        PayScoreNotifyController controller = new PayScoreNotifyController(
                notifyService, payScoreService, payScoreOrderNotifyService);
        when(notifyService.parseAndVerify("body", "ts", "nonce-1", "sig", "serial"))
                .thenThrow(new IllegalStateException("parse failed"));

        assertThrows(IllegalStateException.class, () -> controller.notify(
                "body", "ts", "nonce-1", "sig", "serial", null));

        // parseAndVerify 自己负责解析失败归还；控制器不能把验签/REPLAY 错误也删槽位。
        verify(notifyService, never()).releaseNonce(any());
        verifyNoInteractions(payScoreService, payScoreOrderNotifyService);
    }

    @Test
    void orderEventFailure_releasesNonceForWechatRetry() throws Exception {
        PayScoreNotifyController controller = new PayScoreNotifyController(
                notifyService, payScoreService, payScoreOrderNotifyService);
        JsonNode event = new ObjectMapper().readTree("{\"out_order_no\":\"PSU-1\",\"state\":\"DONE\"}");
        when(notifyService.parseAndVerify("body", "ts", "nonce-1", "sig", "serial"))
                .thenReturn(event);
        doThrow(new IllegalStateException("state update failed"))
                .when(payScoreOrderNotifyService).handleOrderNotify(event);

        assertThrows(IllegalStateException.class, () -> controller.notify(
                "body", "ts", "nonce-1", "sig", "serial", null));

        verify(notifyService).releaseNonce("nonce-1");
        verifyNoInteractions(payScoreService);
    }

    @Test
    void handlingSuccess_keepsNonceConsumed() throws Exception {
        PayScoreNotifyController controller = new PayScoreNotifyController(
                notifyService, payScoreService, payScoreOrderNotifyService);
        JsonNode event = new ObjectMapper().readTree("{\"authorization_code\":\"auth-1\"}");
        when(notifyService.parseAndVerify("body", "ts", "nonce-1", "sig", "serial"))
                .thenReturn(event);

        controller.notify("body", "ts", "nonce-1", "sig", "serial", null);

        verify(payScoreService).handleWechatPermissionNotify(event);
        verify(notifyService, never()).releaseNonce(any());
    }
}
