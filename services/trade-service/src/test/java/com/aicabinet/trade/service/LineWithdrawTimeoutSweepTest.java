package com.aicabinet.trade.service;

import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H38/H26：线长提现 PAYING 超时兜底置 FAILED 并解冻；打款失败同样释放冻结。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineWithdrawTimeoutSweepTest {

    @Mock private LineWithdrawRequestMapper withdrawMapper;
    @Mock private LineManagerMapper managerMapper;
    @Mock private LineDeviceMapper deviceMapper;
    @Mock private LineManagerService lineManagerService;
    @Mock private LineWalletService lineWalletService;
    @Mock private LineWithdrawPayoutService payoutService;
    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PayoutAccountMapper payoutAccountMapper;
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    /** V321 提现资质门禁。 */
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private LineWithdrawService service;

    @BeforeEach
    void setUp() {
        LineWithdrawProperties properties =
                // V308：第 3 位是新增的 maxAmountCents（0 = 单笔不限）
                new LineWithdrawProperties(true, 100, 0L, 500_000, 50_000, 0, 0);
        service = new LineWithdrawService(withdrawMapper, managerMapper, deviceMapper,
                lineManagerService, lineWalletService, payoutService, properties,
                permissionService, auditService, distributedLockService, null,
                WithdrawPolicyResolver.ymlOnly(properties),
                payoutAccountService, payoutAccountMapper, payoutChannelRegistry, withdrawEligibilityService, null);
        // V308：申请时必须锁定收款账户（快照），故并发/超时测试也要提供收款账户服务。
        // 用 lenient()：部分用例（锁冲突、驳回、陈旧扫描）**不会**走到申请落库，
        //    严格模式下这类未被消费的 stub 会报 UnnecessaryStubbing 而让用例变红。
        lenient().when(payoutAccountService.resolveForApply(any(), any(), any()))
                .thenReturn(stubLinePayeeAccount());
        lenient().when(payoutChannelRegistry.find(any())).thenReturn(java.util.Optional.empty());
        // 出款时按申请快照取回真实账户（V308）；返回空则回落默认账户，行为等价
        lenient().when(payoutAccountMapper.findByAccountId(anyLong())).thenReturn(java.util.Optional.empty());
        lenient().when(withdrawMapper.sumAmountByChannelSince(any(), any())).thenReturn(0L);
        lenient().when(withdrawMapper.sumAmountByPayeeSince(any(), any())).thenReturn(0L);

        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void failStalePayingWithdraws_marksFailedAndReleases() {
        LineWithdrawRequest stale = new LineWithdrawRequest();
        stale.setRequestId(55L);
        stale.setManagerId(8L);
        stale.setAmountCents(10_000L);
        stale.setStatus("PAYING");
        stale.setPayChannel("MOCK");
        when(withdrawMapper.findByStatusAndUpdatedAtBefore(eq("PAYING"), any())).thenReturn(List.of(stale));
        when(withdrawMapper.findById(55L)).thenReturn(Optional.of(stale));

        assertEquals(1, service.failStalePayingWithdraws());

        assertEquals("FAILED", stale.getStatus());
        verify(lineWalletService).releaseFrozen(eq(8L), eq(10_000L), eq("WITHDRAW"), eq("55"), anyString());
    }

    /** F3：真实渠道 PAYING 超时禁止自动置失败（防「已出款+已解冻」双重支出），转人工核对。 */
    @Test
    void failStalePayingWithdraws_realChannel_skipsAutoFail() {
        LineWithdrawRequest stale = new LineWithdrawRequest();
        stale.setRequestId(57L);
        stale.setManagerId(8L);
        stale.setAmountCents(10_000L);
        stale.setStatus("PAYING");
        stale.setPayChannel("WECHAT");
        when(withdrawMapper.findByStatusAndUpdatedAtBefore(eq("PAYING"), any())).thenReturn(List.of(stale));
        when(withdrawMapper.findById(57L)).thenReturn(Optional.of(stale));

        assertEquals(0, service.failStalePayingWithdraws());

        assertEquals("PAYING", stale.getStatus());
        verify(lineWalletService, never()).releaseFrozen(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(auditService).appendLog(eq(0L), eq("LINE_WITHDRAW_PAYOUT_STALE_MANUAL"),
                eq("LINE_WITHDRAW"), eq("57"), anyString());
    }

    @Test
    void payoutFailure_releasesFrozen() {
        LineManager manager = new LineManager();
        manager.setManagerId(8L);
        LineWithdrawRequest request = new LineWithdrawRequest();
        request.setRequestId(56L);
        request.setManagerId(8L);
        request.setAmountCents(10_000L);
        request.setStatus("APPROVED");
        when(withdrawMapper.findById(56L)).thenReturn(Optional.of(request));
        when(lineManagerService.requireManager(8L)).thenReturn(manager);
        when(payoutService.payout(any(), eq(manager), any())).thenReturn(
                LineWithdrawPayoutService.PayoutResult.failure("WECHAT", null, "渠道失败"));

        service.executePayout(56L);

        assertEquals("FAILED", request.getStatus());
        verify(lineWalletService).releaseFrozen(eq(8L), eq(10_000L), eq("WITHDRAW"), eq("56"), anyString());
        verify(lineWalletService, never()).consumeFrozen(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }
    /**
     * 造一个可用的线长收款账户（WECHAT 对私，与线长主体是自然人一致）。
     */
    private static PayoutAccount stubLinePayeeAccount() {
        PayoutAccount account = new PayoutAccount();
        account.setAccountId(11L);
        account.setOwnerType(PayoutConstants.PAYEE_OWNER_LINE_MANAGER);
        account.setAccountType(PayoutConstants.PAYEE_TYPE_PERSONAL);
        account.setChannel(com.aicabinet.common.constants.CabinetConstants.PAY_CHANNEL_WECHAT);
        account.setAccountName("测试线长");
        account.setAccountNoMask("****0002");
        account.setStatus("ACTIVE");
        account.setIsDefault(true);
        return account;
    }
}
