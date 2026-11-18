package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3b：冻结判据必须与结算判据同源——「本次会话是否创建了微信支付分单」。
 *
 * <p>旧判据按扫码渠道的「免密就绪」推断：只有支付宝签约的用户从支付宝码进来时跳过冻结，
 * 而 openServiceOrderForShopping 只认微信支付分、不会建单 ⇒ 结算回落余额时账户没冻过钱。
 * 这两条用例把「建单先于冻结」和「skipFreeze == 建单结果」钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionOpenFreezeCriterionTest {

    @Mock private ShoppingSessionMapper repository;
    @Mock private UserValidationService userValidationService;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private MerchantOpsPolicyService opsPolicyService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;
    @Mock private SessionService sessionService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private PayScoreService payScoreService;

    private SessionOpenService newService() {
        SessionOpenService svc = new SessionOpenService(
                repository, userValidationService, deviceValidationService, opsPolicyService,
                consumerPreauthService, displaySnapshotHelper, sessionService,
                distributedLockService, opsAlertDispatcher, payScoreService);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "sessionService", sessionService);
        return svc;
    }

    private ArgumentCaptor<Boolean> open(String entryChannel, boolean payscoreOrderCreated) {
        when(consumerPreauthService.resolvePreauthCents("CAB-001")).thenReturn(1000);
        when(payScoreService.hasOpenWechatOrderForSession(anyString())).thenReturn(payscoreOrderCreated);

        newService().persistConsumerOpeningSession(7L,
                new CreateSessionRequest("CAB-001", null, entryChannel));

        ArgumentCaptor<Boolean> skipFreeze = ArgumentCaptor.forClass(Boolean.class);
        verify(consumerPreauthService).freezeForOpen(any(), skipFreeze.capture());
        return skipFreeze;
    }

    @Test
    void alipayEntryWithNoPayscoreOrder_stillFreezesBalance() {
        // 用户只有支付宝签约、从支付宝码进来：旧判据 passwordFree=true ⇒ 不冻，但根本没有支付分单可收。
        ArgumentCaptor<Boolean> skipFreeze = open("alipay", false);
        assertFalse(skipFreeze.getValue());
    }

    @Test
    void payscoreOrderCreated_skipsBalanceFreeze() {
        ArgumentCaptor<Boolean> skipFreeze = open("wechat", true);
        assertTrue(skipFreeze.getValue());
    }

    @Test
    void payscoreOrderIsCreatedBeforeFreezeDecision() {
        when(consumerPreauthService.resolvePreauthCents("CAB-001")).thenReturn(1000);
        when(payScoreService.hasOpenWechatOrderForSession(anyString())).thenReturn(true);

        SessionOpenService svc = newService();
        svc.persistConsumerOpeningSession(7L,
                new CreateSessionRequest("CAB-001", null, "wechat"));

        InOrder order = inOrder(payScoreService, consumerPreauthService);
        order.verify(payScoreService).openServiceOrderForShopping(any(), anyInt());
        order.verify(payScoreService).hasOpenWechatOrderForSession(anyString());
        order.verify(consumerPreauthService).freezeForOpen(any(), org.mockito.ArgumentMatchers.eq(true));
    }
}
