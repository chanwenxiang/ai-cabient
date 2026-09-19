package com.aicabinet.trade.service;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.aicabinet.trade.domain.BalanceRefundAllocation;
import com.aicabinet.trade.domain.BalanceRefundRequest;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.BalanceRefundAllocationMapper;
import com.aicabinet.trade.mapper.BalanceRefundRequestMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C05/C20：余额退款三段式资金流（扣减→渠道→终态）+ 审核锁内行锁重查。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceRefundReviewFlowTest {
    static {
        // M01 门禁修复引入 LambdaUpdateWrapper：纯 Mockito 环境需手动注册 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BalanceRefundRequest.class);
    }


    @Mock private BalanceRefundRequestMapper requestMapper;
    @Mock private BalanceRefundAllocationMapper allocationMapper;
    @Mock private UserAccountMapper accountMapper;
    @Mock private RechargeOrderMapper rechargeOrderMapper;
    @Mock private PaymentService paymentService;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private SystemConfigService systemConfigService;

    private BalanceRefundService service;

    @BeforeEach
    void setUp() {
        service = new BalanceRefundService(
                requestMapper, allocationMapper, accountMapper, rechargeOrderMapper,
                paymentService, balanceLedgerService, permissionService, auditService,
                distributedLockService, approvalWorkflowService, systemConfigService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    private BalanceRefundRequest pendingRequest() {
        BalanceRefundRequest req = new BalanceRefundRequest();
        req.setRequestId(77L);
        req.setRequestNo("BRTL77");
        req.setUserId(10001L);
        req.setAmountCents(600);
        req.setStatus("PENDING_REVIEW");
        return req;
    }

    private UserAccount account() {
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(2000);
        account.setFrozenCents(600);
        return account;
    }

    private List<RechargeOrder> refundables() {
        RechargeOrder first = new RechargeOrder();
        first.setOrderId("RC-1");
        first.setAmountCents(500);
        first.setRefundedCents(0);
        first.setChannel("WECHAT");
        RechargeOrder second = new RechargeOrder();
        second.setOrderId("RC-2");
        second.setAmountCents(500);
        second.setRefundedCents(0);
        second.setChannel("WECHAT");
        return List.of(first, second);
    }

    /** C05(a)：outRefundNo 稳定按切片序号（BR{id}-S{n}），重试不换号。 */
    @Test
    void debitAndMarkProcessing_assignsStableSliceNumbersAndDebits() {
        BalanceRefundRequest req = pendingRequest();
        when(requestMapper.selectByIdForUpdate(77L)).thenReturn(Optional.of(req));
        when(rechargeOrderMapper.findRefundablePaidByUser(10001L)).thenReturn(refundables());
        when(accountMapper.findByIdForUpdate(10001L)).thenReturn(Optional.of(account()));
        when(accountMapper.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BalanceRefundAllocation> allocations = service.debitAndMarkProcessing(77L);

        assertEquals(2, allocations.size());
        assertEquals("BR77-S1", allocations.get(0).getOutRefundNo());
        assertEquals(500, allocations.get(0).getAmountCents());
        assertEquals("BR77-S2", allocations.get(1).getOutRefundNo());
        assertEquals(100, allocations.get(1).getAmountCents());
        // 先扣减余额（capture 冻结）再落 PROCESSING
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountMapper).save(accountCaptor.capture());
        assertEquals(1400, accountCaptor.getValue().getBalanceCents());
        assertEquals(0, accountCaptor.getValue().getFrozenCents());
        ArgumentCaptor<BalanceRefundRequest> reqCaptor =
                ArgumentCaptor.forClass(BalanceRefundRequest.class);
        verify(requestMapper).updateById(reqCaptor.capture());
        assertEquals("PROCESSING", reqCaptor.getValue().getStatus());
        // 事务① 即落切片（H63 断点续退的进度依据），outRefundNo 按序号稳定
        verify(allocationMapper, org.mockito.Mockito.times(2)).insert(any(BalanceRefundAllocation.class));
    }

    /** C20：锁内重查行后做状态守卫——非 PENDING 直接 409，不再触达资金。 */
    @Test
    void review_whenRowNoLongerPending_rejectsWithConflict() {
        BalanceRefundRequest snapshot = pendingRequest();
        BalanceRefundRequest stale = pendingRequest();
        stale.setStatus("REFUNDED");
        when(requestMapper.selectById(77L)).thenReturn(snapshot);
        when(requestMapper.selectByIdForUpdate(77L)).thenReturn(Optional.of(stale));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.review(1L, 77L, true, "late review"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(paymentService, never()).refundRechargeChannelPartial(
                anyString(), anyInt(), anyString(), anyString());
        verify(balanceLedgerService, never()).change(
                anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    /** C05(b)：渠道退款放在扣减提交之后；部分成功时未成功切片冲回并置 PARTIAL（H63 可续退）。 */
    @Test
    void review_channelFailsAfterDebit_reversesUnsuccessfulSlicesAndMarksPartial() {
        BalanceRefundRequest req = pendingRequest();
        when(requestMapper.selectById(77L)).thenReturn(req);
        when(requestMapper.selectByIdForUpdate(77L)).thenReturn(Optional.of(req));
        when(rechargeOrderMapper.findRefundablePaidByUser(10001L)).thenReturn(refundables());
        when(accountMapper.findByIdForUpdate(10001L)).thenReturn(Optional.of(account()));
        when(accountMapper.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.isInstanceApproved("BALANCE_REFUND", "77")).thenReturn(true);
        doNothing().when(approvalWorkflowService).completeApproved(
                anyLong(), anyString(), anyString(), any());
        doNothing().when(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
        org.mockito.Mockito.doReturn("RF-MOCK")
                .doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "wechat refund failed"))
                .when(paymentService).refundRechargeChannelPartial(
                        anyString(), anyInt(), anyString(), anyString());

        ResponseStatusException wrapped = assertThrows(ResponseStatusException.class,
                () -> service.review(1L, 77L, true, "approve"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, wrapped.getStatusCode());
        assertInstanceOf(IllegalStateException.class, wrapped.getCause());

        // 切片一成功、切片二失败：只冲回切片二（100 分），并置 PARTIAL（保留进度可续退）
        verify(paymentService).refundRechargeChannelPartial(
                eq("RC-1"), eq(500), anyString(), eq("BR77-S1"));
        verify(paymentService).refundRechargeChannelPartial(
                eq("RC-2"), eq(100), anyString(), eq("BR77-S2"));
        verify(balanceLedgerService).change(eq(10001L), eq(100), eq("BALANCE_REFUND_REVERSAL"),
                eq("77"), eq("BALANCE_REFUND_REVERSAL:BRTL77"), contains("冲回"));
        ArgumentCaptor<BalanceRefundRequest> reqCaptor =
                ArgumentCaptor.forClass(BalanceRefundRequest.class);
        verify(requestMapper, org.mockito.Mockito.times(2)).updateById(reqCaptor.capture());
        assertEquals("PARTIAL", reqCaptor.getValue().getStatus());
        assertTrue(reqCaptor.getValue().getFailReason() != null);
    }

    /** C05(b)：首个切片即失败 → 无成功切片，冲回全部扣减并置 FAILED。 */
    @Test
    void review_allSlicesFailOnFirstChannelCall_marksFailedAndReversesAll() {
        BalanceRefundRequest req = pendingRequest();
        when(requestMapper.selectById(77L)).thenReturn(req);
        when(requestMapper.selectByIdForUpdate(77L)).thenReturn(Optional.of(req));
        when(rechargeOrderMapper.findRefundablePaidByUser(10001L)).thenReturn(refundables());
        when(accountMapper.findByIdForUpdate(10001L)).thenReturn(Optional.of(account()));
        when(accountMapper.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.isInstanceApproved("BALANCE_REFUND", "77")).thenReturn(true);
        doNothing().when(approvalWorkflowService).completeApproved(
                anyLong(), anyString(), anyString(), any());
        doNothing().when(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "wechat down"))
                .when(paymentService).refundRechargeChannelPartial(
                        anyString(), anyInt(), anyString(), anyString());

        ResponseStatusException wrappedAll = assertThrows(ResponseStatusException.class,
                () -> service.review(1L, 77L, true, "approve"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, wrappedAll.getStatusCode());
        assertInstanceOf(IllegalStateException.class, wrappedAll.getCause());

        verify(balanceLedgerService).change(eq(10001L), eq(600), eq("BALANCE_REFUND_REVERSAL"),
                eq("77"), eq("BALANCE_REFUND_REVERSAL:BRTL77"), contains("冲回"));
        ArgumentCaptor<BalanceRefundRequest> reqCaptor =
                ArgumentCaptor.forClass(BalanceRefundRequest.class);
        verify(requestMapper, org.mockito.Mockito.times(2)).updateById(reqCaptor.capture());
        assertEquals("FAILED", reqCaptor.getValue().getStatus());
    }

    /** C05：全部切片渠道成功 → REFUNDED 终态。 */
    @Test
    void review_allChannelSlicesSucceed_marksRefunded() {
        BalanceRefundRequest req = pendingRequest();
        when(requestMapper.selectById(77L)).thenReturn(req);
        when(requestMapper.selectByIdForUpdate(77L)).thenReturn(Optional.of(req));
        when(rechargeOrderMapper.findRefundablePaidByUser(10001L)).thenReturn(refundables());
        when(accountMapper.findByIdForUpdate(10001L)).thenReturn(Optional.of(account()));
        when(accountMapper.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.isInstanceApproved("BALANCE_REFUND", "77")).thenReturn(true);
        doNothing().when(approvalWorkflowService).completeApproved(
                anyLong(), anyString(), anyString(), any());
        doNothing().when(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
        org.mockito.Mockito.doReturn("RF-MOCK").when(paymentService).refundRechargeChannelPartial(
                anyString(), anyInt(), anyString(), anyString());

        var dto = service.review(1L, 77L, true, "approve");

        assertEquals("REFUNDED", dto.status());
        verify(balanceLedgerService, never()).change(
                anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }
}
