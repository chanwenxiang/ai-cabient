package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.BalanceRefundAllocation;
import com.aicabinet.trade.domain.BalanceRefundRequest;
import com.aicabinet.trade.mapper.BalanceRefundAllocationMapper;
import com.aicabinet.trade.mapper.BalanceRefundRequestMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-140（2026-10-18 批次 3）回归钉：余额退款切片渠道异常后的三态查单确认。
 * <p>旧实现「渠道异常=失败」⇒ 冲回 + 续退换 outRefundNo 重退 ⇒ HTTP 超时但渠道已受理时
 * 双退，且 WeChatRefundReconciler 不覆盖余额切片号（无对账兜底）。修复后：
 * ACCEPTED（SUCCESS/PROCESSING）按成功推进并补渠道 finalize；NOT_FOUND（404/ABNORMAL/CLOSED）
 * 走原失败冲回；UNKNOWN（支付宝/未配置/查单异常）保守维持旧路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class BalanceRefundSliceProbeTest {

    @Mock BalanceRefundRequestMapper requestMapper;
    @Mock BalanceRefundAllocationMapper allocationMapper;
    @Mock UserAccountMapper accountMapper;
    @Mock RechargeOrderMapper rechargeOrderMapper;
    @Mock PaymentService paymentService;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock PermissionService permissionService;
    @Mock AdminAuditService auditService;
    @Mock DistributedLockService distributedLockService;
    @Mock ApprovalWorkflowService approvalWorkflowService;
    @Mock SystemConfigService systemConfigService;

    BalanceRefundService service;
    BalanceRefundService spySelf;

    @BeforeEach
    void setUp() {
        service = new BalanceRefundService(requestMapper, allocationMapper, accountMapper,
                rechargeOrderMapper, paymentService, balanceLedgerService, permissionService,
                auditService, distributedLockService, approvalWorkflowService, systemConfigService,
                null);
        // runChannelSlices 经 self 调 markRefundSuccess / markRefundFailedAndReverse——
        // 用 spy 自注入隔离这两个落库面，聚焦三态分支本身
        spySelf = spy(service);
        ReflectionTestUtils.setField(service, "self", spySelf);
    }

    private BalanceRefundRequest req() {
        BalanceRefundRequest r = new BalanceRefundRequest();
        r.setRequestId(901L);
        r.setRequestNo("BR-901");
        r.setUserId(903L);
        r.setAmountCents(400);
        r.setStatus("PROCESSING");
        return r;
    }

    private BalanceRefundAllocation alloc() {
        BalanceRefundAllocation a = new BalanceRefundAllocation();
        a.setRequestId(901L);
        a.setRechargeOrderId("RC-1");
        a.setAmountCents(400);
        a.setOutRefundNo("BR901-S1");
        return a;
    }

    private void channelThrows() {
        when(paymentService.refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("channel read timeout"));
    }

    @Test
    void probeAccepted_countsSliceSucceeded_noReverse() {
        BalanceRefundRequest r = req();
        channelThrows();
        when(paymentService.probeChannelRefundAccepted("RC-1", "BR901-S1"))
                .thenReturn(PaymentService.SliceRefundProbe.ACCEPTED);
        doReturn(r).when(spySelf).markRefundSuccess(901L);

        BalanceRefundRequest out = ReflectionTestUtils.invokeMethod(service, "runChannelSlices",
                r, List.of(alloc()), List.of());

        assertEquals(r, out);
        verify(paymentService).finalizeChannelPartialRefundQuietly("RC-1", 400);
        verify(spySelf, never()).markRefundFailedAndReverse(anyLong(), anyList(), any(RuntimeException.class));
    }

    @Test
    void probeNotFound_keepsLegacyFailAndReverse() {
        BalanceRefundRequest r = req();
        channelThrows();
        when(paymentService.probeChannelRefundAccepted("RC-1", "BR901-S1"))
                .thenReturn(PaymentService.SliceRefundProbe.NOT_FOUND);
        doReturn(r).when(spySelf).markRefundFailedAndReverse(anyLong(), anyList(), any(RuntimeException.class));

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "runChannelSlices",
                        r, List.of(alloc()), List.of()));

        verify(spySelf).markRefundFailedAndReverse(anyLong(), anyList(), any(RuntimeException.class));
        verify(paymentService, never()).finalizeChannelPartialRefundQuietly(anyString(), anyInt());
    }

    @Test
    void probeUnknown_keepsLegacyFailAndReverse() {
        BalanceRefundRequest r = req();
        channelThrows();
        when(paymentService.probeChannelRefundAccepted("RC-1", "BR901-S1"))
                .thenReturn(PaymentService.SliceRefundProbe.UNKNOWN);
        doReturn(r).when(spySelf).markRefundFailedAndReverse(anyLong(), anyList(), any(RuntimeException.class));

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "runChannelSlices",
                        r, List.of(alloc()), List.of()));

        verify(spySelf).markRefundFailedAndReverse(anyLong(), anyList(), any(RuntimeException.class));
        verify(paymentService, never()).finalizeChannelPartialRefundQuietly(anyString(), anyInt());
    }

    /** probe 无异常路径不受影响：渠道正常成功时不得触发查单。 */
    @Test
    void channelSuccess_doesNotProbe() {
        BalanceRefundRequest r = req();
        when(paymentService.refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("BR901-S1");
        doReturn(r).when(spySelf).markRefundSuccess(901L);

        ReflectionTestUtils.invokeMethod(service, "runChannelSlices",
                r, List.of(alloc()), List.of());

        verify(paymentService, never()).probeChannelRefundAccepted(anyString(), anyString());
    }
}
