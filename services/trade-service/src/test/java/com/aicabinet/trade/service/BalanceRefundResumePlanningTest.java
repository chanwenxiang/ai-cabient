package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.BalanceRefundAllocation;
import com.aicabinet.trade.domain.BalanceRefundRequest;
import com.aicabinet.trade.domain.RechargeOrder;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-137（2026-10-18 批次 4）定案回归钉：续退规划改读切片行级状态（V393）。
 * <p>根因：旧实现 skipOrderIds 跳过全部已用充值单且 succeededCents 按 Σ全部切片（含失败
 * 已冲回）计算 ⇒ 部分成功+部分失败时 remain=0 且 resumed.isEmpty() ⇒ 谎报 REFUNDED
 * （用户损失失败切片金额）。修复后：只跳过 SUCCEEDED 切片的单，失败切片金额重新补扣，
 * 新切片落 status=PROCESSING；全部切片确认 SUCCEEDED 才允许置 REFUNDED（防御在
 * resumePartialRefund，本类聚焦规划层）。</p>
 */
@ExtendWith(MockitoExtension.class)
class BalanceRefundResumePlanningTest {

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

    @BeforeEach
    void setUp() {
        service = new BalanceRefundService(requestMapper, allocationMapper, accountMapper,
                rechargeOrderMapper, paymentService, balanceLedgerService, permissionService,
                auditService, distributedLockService, approvalWorkflowService, systemConfigService,
                null);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private BalanceRefundRequest req() {
        BalanceRefundRequest r = new BalanceRefundRequest();
        r.setRequestId(911L);
        r.setRequestNo("BR-911");
        r.setUserId(913L);
        r.setAmountCents(1000);
        r.setStatus("PARTIAL");
        return r;
    }

    private BalanceRefundAllocation slice(long id, String rechargeOrder, int cents, String status) {
        BalanceRefundAllocation a = new BalanceRefundAllocation();
        a.setAllocationId(id);
        a.setRequestId(911L);
        a.setRechargeOrderId(rechargeOrder);
        a.setAmountCents(cents);
        a.setChannel("WECHAT");
        a.setOutRefundNo("BR911-S" + id);
        a.setStatus(status);
        a.setCreatedAt(Instant.now());
        return a;
    }

    private RechargeOrder recharge(String orderId, int amount, int refunded) {
        RechargeOrder o = new RechargeOrder();
        o.setOrderId(orderId);
        o.setUserId(913L);
        o.setChannel("WECHAT");
        o.setAmountCents(amount);
        o.setRefundedCents(refunded);
        o.setStatus("PAID");
        return o;
    }

    /** 混合成败切片：SUCCEEDED 400(RC-1) + FAILED 600(RC-2) ⇒ 只跳 RC-1，补扣 600 优先退 RC-2。 */
    @Test
    void resumePlanning_skipsOnlySucceededSlices_redebitsFailedAmount() {
        BalanceRefundRequest r = req();
        when(requestMapper.selectByIdForUpdate(911L)).thenReturn(Optional.of(r));
        when(allocationMapper.findByRequestId(911L)).thenReturn(List.of(
                slice(1, "RC-1", 400, "SUCCEEDED"),
                slice(2, "RC-2", 600, "FAILED")));
        // RC-2 渠道未受理（AU-140 finalize 未跑）⇒ 可退额充足，优先原单重退；RC-3 兜底
        when(rechargeOrderMapper.findRefundablePaidByUser(913L)).thenReturn(List.of(
                recharge("RC-2", 800, 0),
                recharge("RC-3", 700, 0)));

        List<BalanceRefundAllocation> resumed = ReflectionTestUtils.invokeMethod(
                service, "debitRemainingAndMarkProcessing", 911L);

        assertEquals(1, resumed.size(), "失败切片 600 应在 RC-2 上重新规划（非谎报无可退）");
        assertEquals("RC-2", resumed.get(0).getRechargeOrderId());
        assertEquals(600, resumed.get(0).getAmountCents());
        assertEquals("PROCESSING", resumed.get(0).getStatus(), "新切片落行级 PROCESSING");
        assertEquals("BR911-S3", resumed.get(0).getOutRefundNo(), "切片号接续 done.size()=2");
        // 补扣 = 失败切片金额（旧实现 Σ全部切片 ⇒ 补扣 0 ⇒ 谎报 REFUNDED）
        verify(balanceLedgerService).change(eq(913L), eq(-600), anyString(), anyString(), anyString(), anyString());
    }

    /** 全部切片 SUCCEEDED：resumed 为空（无可续），由 resumePartialRefund 防御分支置 REFUNDED——规划层返回空即可。 */
    @Test
    void resumePlanning_allSucceeded_returnsEmpty() {
        BalanceRefundRequest r = req();
        when(requestMapper.selectByIdForUpdate(911L)).thenReturn(Optional.of(r));
        when(allocationMapper.findByRequestId(911L)).thenReturn(List.of(
                slice(1, "RC-1", 600, "SUCCEEDED"),
                slice(2, "RC-2", 400, "SUCCEEDED")));

        List<BalanceRefundAllocation> resumed = ReflectionTestUtils.invokeMethod(
                service, "debitRemainingAndMarkProcessing", 911L);

        assertEquals(0, resumed.size(), "无未成功切片 ⇒ 规划为空（防御分支据切片状态置 REFUNDED）");
        verify(balanceLedgerService, never()).change(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    /** 防 Oracle：change 负数入参语义（补扣走 change(-cents)）。 */
    @Test
    void resumePlanning_debitUsesNegativeChange() {
        BalanceRefundRequest r = req();
        when(requestMapper.selectByIdForUpdate(911L)).thenReturn(Optional.of(r));
        when(allocationMapper.findByRequestId(911L)).thenReturn(List.of(
                slice(1, "RC-1", 400, "SUCCEEDED"),
                slice(2, "RC-2", 600, "FAILED")));
        when(rechargeOrderMapper.findRefundablePaidByUser(913L)).thenReturn(List.of(
                recharge("RC-2", 800, 0), recharge("RC-3", 700, 0)));

        ReflectionTestUtils.invokeMethod(service, "debitRemainingAndMarkProcessing", 911L);

        verify(balanceLedgerService).change(eq(913L), eq(-600), eq("BALANCE_REFUND"),
                eq("911"), eq("BALANCE_REFUND_RESUME:BR-911"), anyString());
    }
}
