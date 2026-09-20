package com.aicabinet.trade.service;

import com.aicabinet.common.dto.BalanceRefundRequestDto;
import com.aicabinet.trade.domain.BalanceRefundRequest;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.BalanceRefundAllocationMapper;
import com.aicabinet.trade.mapper.BalanceRefundRequestMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O6：余额退款自动审批（{@code refund.auto_approve.max_cents}）。
 * <p>三条不变式：阈值 0（默认关闭）⇒ 与接入前完全一致（转人工 + 建审批实例）；
 * 阈值内 ⇒ 系统账号免审并立即原路退款、且不建审批实例；超阈值 ⇒ 仍转人工。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceRefundAutoApproveTest {
    static {
        // 与 BalanceRefundReviewFlowTest 同因：LambdaUpdateWrapper 需要 TableInfo 缓存（M01）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BalanceRefundRequest.class);
    }

    private static final long REQ_ID = 77L;
    private static final long USER_ID = 10001L;
    private static final String AUTO_KEY = SystemConfigService.REFUND_AUTO_APPROVE_MAX_CENTS;

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
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        // IdType.AUTO 的真实行为：insert 后回填自增主键（否则 doApply 之后的链路拿不到 requestId）
        lenient().when(requestMapper.insert(any(BalanceRefundRequest.class))).thenAnswer(inv -> {
            inv.getArgument(0, BalanceRefundRequest.class).setRequestId(REQ_ID);
            return 1;
        });
        lenient().when(requestMapper.countByUserIdAndStatus(anyLong(), anyString())).thenReturn(0L);
    }

    private void stubApplicantFunds() {
        UserAccount account = new UserAccount();
        account.setUserId(USER_ID);
        account.setBalanceCents(2000);
        account.setFrozenCents(0);
        lenient().when(accountMapper.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(account));
        lenient().when(accountMapper.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rechargeOrderMapper.findRefundablePaidByUser(USER_ID)).thenReturn(refundables());
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

    private BalanceRefundRequest pending() {
        BalanceRefundRequest req = new BalanceRefundRequest();
        req.setRequestId(REQ_ID);
        req.setRequestNo("BRTL77");
        req.setUserId(USER_ID);
        req.setAmountCents(600);
        req.setStatus("PENDING_REVIEW");
        return req;
    }

    private void stubPersistedRequest() {
        BalanceRefundRequest req = pending();
        when(requestMapper.selectById(REQ_ID)).thenReturn(req);
        when(requestMapper.selectByIdForUpdate(REQ_ID)).thenReturn(Optional.of(req));
    }

    /** 默认关闭：不自动审批，创建审批实例、不触达渠道，reviewer 保持空。 */
    @Test
    void apply_whenDisabled_staysPendingWithApprovalInstanceAndNoChannelCall() {
        when(systemConfigService.getInt(AUTO_KEY, 0)).thenReturn(0);
        stubApplicantFunds();

        BalanceRefundRequestDto dto = service.apply(USER_ID, 600, "不想要了");

        assertEquals("PENDING_REVIEW", dto.status());
        assertNull(dto.reviewerId());
        verify(approvalWorkflowService).start(eq("BALANCE_REFUND"), anyString(), eq(USER_ID), anyString());
        verify(paymentService, never())
                .refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString());
    }

    /** 阈值内：系统账号免审并原路退款，且**不创建**审批实例（否则人工审批台留无人认领的待办）。 */
    @Test
    void apply_whenWithinThreshold_autoApprovesWithSystemReviewer() {
        when(systemConfigService.getInt(AUTO_KEY, 0)).thenReturn(10_000);
        stubApplicantFunds();
        stubPersistedRequest();
        doReturn("RF-MOCK").when(paymentService)
                .refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString());

        BalanceRefundRequestDto dto = service.apply(USER_ID, 600, "不想要了");

        assertEquals("REFUNDED", dto.status());
        assertEquals(Long.valueOf(BalanceRefundService.AUTO_REVIEWER_ID), dto.reviewerId());
        assertNotNull(dto.reviewedAt());
        verify(approvalWorkflowService, never()).start(anyString(), anyString(), any(), anyString());
        verify(auditService).appendLog(eq(BalanceRefundService.AUTO_REVIEWER_ID),
                eq("BALANCE_REFUND_AUTO_APPROVE"), eq("BALANCE_REFUND"), eq(String.valueOf(REQ_ID)),
                contains("系统自动审批"));
        // 600 分 = 500 + 100 两片
        verify(paymentService, times(2))
                .refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString());
    }

    /** 边界：金额恰好等于阈值 ⇒ 仍在自动审批范围内。 */
    @Test
    void apply_whenAmountEqualsThreshold_autoApproves() {
        when(systemConfigService.getInt(AUTO_KEY, 0)).thenReturn(600);
        stubApplicantFunds();
        stubPersistedRequest();
        doReturn("RF-MOCK").when(paymentService)
                .refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString());

        assertEquals("REFUNDED", service.apply(USER_ID, 600, "边界").status());
    }

    /** 超过阈值：仍转人工，渠道不被触达。 */
    @Test
    void apply_whenAboveThreshold_staysPendingForHumanReview() {
        when(systemConfigService.getInt(AUTO_KEY, 0)).thenReturn(100);
        stubApplicantFunds();

        BalanceRefundRequestDto dto = service.apply(USER_ID, 600, "超额");

        assertEquals("PENDING_REVIEW", dto.status());
        verify(approvalWorkflowService).start(anyString(), anyString(), eq(USER_ID), anyString());
        verify(paymentService, never())
                .refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString());
    }

    /** 渠道失败：资金按既有契约冲回，申请已落库 ⇒ 返回真实状态而不是抛 500。 */
    @Test
    void apply_whenChannelFails_returnsFailedStateInsteadOfError() {
        when(systemConfigService.getInt(AUTO_KEY, 0)).thenReturn(10_000);
        stubApplicantFunds();
        stubPersistedRequest();
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "wechat down"))
                .when(paymentService)
                .refundRechargeChannelPartial(anyString(), anyInt(), anyString(), anyString());

        BalanceRefundRequestDto dto = service.apply(USER_ID, 600, "渠道故障");

        assertEquals("FAILED", dto.status());
        assertNotNull(dto.failReason());
        verify(balanceLedgerService).change(eq(USER_ID), eq(600), eq("BALANCE_REFUND_REVERSAL"),
                eq(String.valueOf(REQ_ID)), anyString(), contains("冲回"));
    }
}
