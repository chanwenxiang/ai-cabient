package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineWithdrawRequestDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineWithdrawConcurrencyTest {

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
        LineWithdrawProperties properties = // V308：第 3 位是新增的 maxAmountCents（0 = 单笔不限）
        new LineWithdrawProperties(true, 100, 0L, 500_000, 50_000, 0, 0);
        service = new LineWithdrawService(
                withdrawMapper, managerMapper, deviceMapper, lineManagerService,
                lineWalletService, payoutService, properties,
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

        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void apply_whenLockBusy_rejectsWithConflict() {
        LineManager manager = new LineManager();
        manager.setManagerId(7L);
        manager.setManagerName("线长7");
        when(lineManagerService.requireManager(7L)).thenReturn(manager);
        when(distributedLockService.tryLock(
                LineWithdrawService.lineWalletLockKey(7L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.apply(7L, 10_000L, "REQ-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void apply_acquiresLockAndFreezesBalance() {
        LineManager manager = new LineManager();
        manager.setManagerId(8L);
        manager.setManagerName("线长8");

        LineWalletAccount account = new LineWalletAccount();
        account.setManagerId(8L);
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        AtomicReference<LineWithdrawRequest> stored = new AtomicReference<>();
        when(lineManagerService.requireManager(8L)).thenReturn(manager);
        when(distributedLockService.tryLock(
                LineWithdrawService.lineWalletLockKey(8L), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-2")).thenReturn(Optional.empty());
        when(deviceMapper.selectCount(any())).thenReturn(1L);
        when(lineWalletService.ensureAccount(8L)).thenReturn(account);
        when(withdrawMapper.sumAmountByManagerSince(eq(8L), any())).thenReturn(0L);
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            LineWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(99L);
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(99L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(manager), any())).thenReturn(
                new LineWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok"));

        LineWithdrawRequestDto dto = service.apply(8L, 10_000L, "REQ-2");

        assertEquals("PAID", dto.status());
        verify(lineWalletService).freezeForWithdraw(eq(8L), eq(10_000L), any(), any(), any());
        verify(distributedLockService).unlock(LineWithdrawService.lineWalletLockKey(8L));
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
