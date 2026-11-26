package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConfirmTicketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * MA-409：微信转账结果通知的<b>渠道状态终态判定</b>（回归钉）。
 *
 * <p>此前这里手抄了一份字面量且含 {@code CANCELING}：渠道还在「撤销中」（官方 4012716434
 * 明确标注非终态，需查单确认），本地就已落 FAILED 释放冻结 ⇒ 账实不符。
 * 修复后终态判定收敛到 {@link WeChatTransferClient#isSuccessState} /
 * {@link WeChatTransferClient#isTerminalFailState} 唯一真值源。本测试按官方 state 全集
 * （3 终态 + 5 非终态 + 未知态）逐个钉住路由行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MA-409 转账结果通知：渠道状态终态判定（CANCELING 非终态回归）")
class MerchantWithdrawNotifyStateTest {

    private static final long REQUEST_ID = 42L;
    private static final String MERCHANT_ID = "M-1";
    private static final String IDEM_KEY = "MW0000000042";

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private MerchantWithdrawPayoutService payoutService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PayoutAccountMapper payoutAccountMapper;
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private PayoutConfirmTicketStore confirmTicketStore;

    private MerchantWithdrawService service;
    private MerchantWithdrawRequest request;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(false, 100, 1_000_000L, 4_000_000L, 50_000L, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper, merchantFeaturePackService, null,
                merchantScopeService, permissionService, auditService, distributedLockService,
                mock(ApprovalWorkflowService.class), WithdrawPolicyResolver.ymlOnly(properties),
                payoutChannelRegistry, orderRevenueSplitMapper, withdrawEligibilityService,
                null, null, confirmTicketStore, null, null, null);
        ReflectionTestUtils.setField(service, "self", service);

        request = new MerchantWithdrawRequest();
        request.setRequestId(REQUEST_ID);
        request.setMerchantId(MERCHANT_ID);
        request.setIdemKey(IDEM_KEY);
        request.setAmountCents(10L);
        request.setFeeCents(0L);
        request.setStatus("PAYING");
        lenient().when(withdrawMapper.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        lenient().when(withdrawMapper.findByIdemKey(IDEM_KEY)).thenReturn(Optional.of(request));
        // 终态闸默认「抢占成功」；重复回调用例里手动置 PAID 模拟竞争失败（0 行）
        lenient().when(withdrawMapper.finalizeIfPaying(anyLong(), anyString(), any(), anyString(), anyString()))
                .thenReturn(1);
    }

    private void assertFinalized(boolean expectedSuccess) {
        InOrder inOrder = inOrder(withdrawMapper, merchantWalletService);
        // 条件 UPDATE（终态闸）必须先于钱包动作
        inOrder.verify(withdrawMapper).finalizeIfPaying(eq(REQUEST_ID),
                eq(expectedSuccess ? "PAID" : "FAILED"), any(), anyString(), anyString());
        if (expectedSuccess) {
            inOrder.verify(merchantWalletService).consumeFrozenSplit(eq(MERCHANT_ID),
                    eq(10L), eq(0L), eq("WITHDRAW"), eq(String.valueOf(REQUEST_ID)), anyString());
        } else {
            inOrder.verify(merchantWalletService).releaseFrozen(eq(MERCHANT_ID), eq(10L),
                    eq("WITHDRAW"), eq(String.valueOf(REQUEST_ID)), anyString());
        }
        inOrder.verifyNoMoreInteractions();
    }

    private void assertNothingHappened() {
        assertEquals("PAYING", request.getStatus());
        verify(withdrawMapper, never()).finalizeIfPaying(anyLong(), anyString(), any(), anyString(), anyString());
        verify(merchantWalletService, never()).releaseFrozen(anyString(), anyLong(),
                anyString(), anyString(), anyString());
        verify(merchantWalletService, never()).consumeFrozenSplit(anyString(), anyLong(),
                anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("SUCCESS（终态）→ PAID + 消费冻结")
    void successFinalizesPaid() {
        assertEquals(Optional.of(REQUEST_ID), service.handleTransferNotify(IDEM_KEY, "SUCCESS", "BILL-S1"));
        assertFinalized(true);
    }

    @Test
    @DisplayName("FAIL / CANCELLED（终态）→ FAILED + 释放冻结")
    void terminalFailFinalizesFailed() {
        assertEquals(Optional.of(REQUEST_ID), service.handleTransferNotify(IDEM_KEY, "FAIL", "BILL-F1"));
        assertFinalized(false);
        clearInvocations(withdrawMapper, merchantWalletService);

        assertEquals(Optional.of(REQUEST_ID), service.handleTransferNotify(IDEM_KEY, "CANCELLED", "BILL-F2"));
        assertFinalized(false);
    }

    @Test
    @DisplayName("🔴 MA-409 回归：CANCELING 是非终态——绝不落 FAILED 释放冻结")
    void cancelingStaysPayingAndKeepsFrozen() {
        assertEquals(Optional.of(REQUEST_ID), service.handleTransferNotify(IDEM_KEY, "CANCELING", "BILL-C1"));
        assertNothingHappened();
    }

    @Test
    @DisplayName("官方 state 全集逐个过一遍：终态三兄弟推进，非终态五兄弟与未知态保持 PAYING")
    void everyDocumentedStateRoutesCorrectly() {
        // 非终态（官方标注）5 个 + 1 个未知态：全部保持 PAYING
        for (String s : new String[]{"ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM",
                "TRANSFERING", "CANCELING", "SOME_FUTURE_STATE"}) {
            assertEquals(Optional.of(REQUEST_ID), service.handleTransferNotify(IDEM_KEY, s, "B-" + s));
            assertNothingHappened();
            clearInvocations(withdrawMapper, merchantWalletService, auditService, confirmTicketStore);
        }
        // 终态 3 个：各自推进一次
        service.handleTransferNotify(IDEM_KEY, "SUCCESS", "B-S");
        assertFinalized(true);
        clearInvocations(withdrawMapper, merchantWalletService, auditService, confirmTicketStore);
        service.handleTransferNotify(IDEM_KEY, "FAIL", "B-F");
        assertFinalized(false);
        clearInvocations(withdrawMapper, merchantWalletService, auditService, confirmTicketStore);
        service.handleTransferNotify(IDEM_KEY, "CANCELLED", "B-C");
        assertFinalized(false);
    }

    @Test
    @DisplayName("重复回调幂等：终态已落，第二次通知 0 行让路，不再重复动钱包")
    void duplicateNotifyIsIdempotent() {
        service.handleTransferNotify(IDEM_KEY, "SUCCESS", "BILL-D1");
        assertFinalized(true);
        clearInvocations(withdrawMapper, merchantWalletService);

        // 单据已落 PAID：哪怕回调再来一次，finalizeIfPaying 0 行放弃，钱包不再动作
        request.setStatus("PAID");
        assertFalse(service.finalizeFromChannel(REQUEST_ID, true, "BILL-D1", "重投"));
        verify(merchantWalletService, never()).consumeFrozenSplit(anyString(), anyLong(),
                anyLong(), anyString(), anyString(), anyString());
        verify(merchantWalletService, never()).releaseFrozen(anyString(), anyLong(),
                anyString(), anyString(), anyString());
    }
}
