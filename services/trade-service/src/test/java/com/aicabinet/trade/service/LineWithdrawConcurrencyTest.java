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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        // 2026-10-21：自动通过路径同样要抢出款权（条件 UPDATE），假表按当前状态判定
        when(withdrawMapper.claimForPayout(anyLong(), anyString(), any())).thenAnswer(inv -> {
            LineWithdrawRequest row = stored.get();
            String fromStatus = inv.getArgument(1);
            if (row == null || !fromStatus.equals(row.getStatus())) {
                return 0;
            }
            row.setStatus("PAYING");
            return 1;
        });
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

    /**
     * AU-006（审计 B-1 F-01，线长同构面）：requestNo 幂等命中他人单必须显式 409，
     * 不得返回他人 DTO，也不得静默吞掉本线长的提现请求。
     */
    @Test
    void persistApply_idempotentHitForeignManager_rejectedAndNotRevealed() {
        LineManager manager = new LineManager();
        manager.setManagerId(8L);
        manager.setManagerName("线长8");
        LineWalletAccount account = new LineWalletAccount();
        account.setManagerId(8L);
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        LineWithdrawRequest foreign = new LineWithdrawRequest();
        foreign.setRequestId(666L);
        foreign.setRequestNo("REQ-TAKEN");
        foreign.setManagerId(9L);
        foreign.setAmountCents(20_000L);
        foreign.setFeeCents(0L);
        foreign.setStatus("PAID");
        foreign.setPayChannel("MOCK");

        when(deviceMapper.selectCount(any())).thenReturn(1L);
        when(lineWalletService.ensureAccount(8L)).thenReturn(account);
        when(withdrawMapper.sumAmountByManagerSince(eq(8L), any())).thenReturn(0L);
        when(withdrawMapper.findByRequestNo("REQ-TAKEN")).thenReturn(Optional.of(foreign));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(manager, 10_000L, "REQ-TAKEN", 1L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(withdrawMapper, org.mockito.Mockito.never()).insert(any());
    }

    /**
     * AU-007（审计 B-1 F-02，线长同构面）：cancelFailed 状态守卫必须锁内重查——
     * 锁前快照 FAILED、锁等待窗口内并发 payout 已置 PAID 时不得再解冻。
     */
    @Test
    void cancelFailed_rereadsStatusInsideLock_paidNotReleased() {
        LineWithdrawRequest stale = new LineWithdrawRequest();
        stale.setRequestId(88L);
        stale.setManagerId(8L);
        stale.setAmountCents(10_000L);
        stale.setStatus("FAILED");

        LineWithdrawRequest fresh = new LineWithdrawRequest();
        fresh.setRequestId(88L);
        fresh.setManagerId(8L);
        fresh.setAmountCents(10_000L);
        fresh.setStatus("PAID");

        when(withdrawMapper.findById(88L)).thenReturn(Optional.of(stale), Optional.of(fresh));
        when(distributedLockService.tryLock(
                LineWithdrawService.lineWalletLockKey(8L), 60L, 5L))
                .thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.cancelFailed(1L, 88L, "放弃打款"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(lineWalletService, org.mockito.Mockito.never()).releaseFrozen(
                anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    // ============ 2026-10-21 出款权四层防线（镜像商户链 MerchantWithdrawDoubleReviewTest）============

    /**
     * 有状态假表：{@code findById} 返回行<b>副本</b>、{@code updateById}/{@code claimForPayout}
     * 按条件写回，所以服务在锁内重查时读到的是「已提交」状态，与真库 READ COMMITTED 同构 ——
     * 用固定返回值 mock 测不出这条守卫。
     */
    private final Map<Long, LineWithdrawRequest> table = new HashMap<>();

    private void wireStatefulTable() {
        lenient().when(withdrawMapper.findById(any())).thenAnswer(inv -> readRow(inv.getArgument(0)));
        lenient().when(withdrawMapper.updateById(any(LineWithdrawRequest.class))).thenAnswer(inv -> {
            LineWithdrawRequest row = inv.getArgument(0);
            synchronized (table) {
                table.put(row.getRequestId(), copyRow(row));
            }
            return 1;
        });
        lenient().when(withdrawMapper.claimForPayout(anyLong(), anyString(), any()))
                .thenAnswer(inv -> claimRow(inv.getArgument(0), inv.getArgument(1)));
    }

    /** 模拟 {@code UPDATE … SET status='PAYING' WHERE request_id=? AND status=?} 的返回行数。 */
    private int claimRow(Long requestId, String fromStatus) {
        synchronized (table) {
            LineWithdrawRequest row = table.get(requestId);
            if (row == null || !fromStatus.equals(row.getStatus())) {
                return 0;
            }
            row.setStatus("PAYING");
            row.setUpdatedAt(Instant.now());
            return 1;
        }
    }

    private Optional<LineWithdrawRequest> readRow(Long requestId) {
        synchronized (table) {
            LineWithdrawRequest row = table.get(requestId);
            return row == null ? Optional.empty() : Optional.of(copyRow(row));
        }
    }

    private static LineWithdrawRequest copyRow(LineWithdrawRequest src) {
        LineWithdrawRequest dst = new LineWithdrawRequest();
        dst.setRequestId(src.getRequestId());
        dst.setRequestNo(src.getRequestNo());
        dst.setManagerId(src.getManagerId());
        dst.setAmountCents(src.getAmountCents());
        dst.setFeeCents(src.getFeeCents());
        dst.setStatus(src.getStatus());
        dst.setPayChannel(src.getPayChannel());
        dst.setReviewerId(src.getReviewerId());
        dst.setReviewRemark(src.getReviewRemark());
        dst.setReviewedAt(src.getReviewedAt());
        dst.setPayoutRef(src.getPayoutRef());
        dst.setPayoutMessage(src.getPayoutMessage());
        dst.setPaidAt(src.getPaidAt());
        dst.setCreatedAt(src.getCreatedAt());
        dst.setUpdatedAt(src.getUpdatedAt());
        dst.setPayoutAccountId(src.getPayoutAccountId());
        dst.setPayeeAccountType(src.getPayeeAccountType());
        dst.setPayeeAccountName(src.getPayeeAccountName());
        dst.setPayeeAccountNoMask(src.getPayeeAccountNoMask());
        dst.setPayeeBankName(src.getPayeeBankName());
        dst.setPayeeTaxNo(src.getPayeeTaxNo());
        dst.setIdemKey(src.getIdemKey());
        dst.setChannelOrderNo(src.getChannelOrderNo());
        dst.setChannelBatchNo(src.getChannelBatchNo());
        return dst;
    }

    private void given(LineWithdrawRequest request) {
        synchronized (table) {
            table.put(request.getRequestId(), copyRow(request));
        }
    }

    private static LineWithdrawRequest lineRequest(String status) {
        LineWithdrawRequest request = new LineWithdrawRequest();
        request.setRequestId(91L);
        request.setRequestNo("LREQ-91");
        request.setManagerId(8L);
        request.setAmountCents(10_000L);
        request.setFeeCents(0L);
        request.setStatus(status);
        request.setPayoutAccountId(11L);
        // 申请时写入的幂等键：重试不换键 ⇒ 渠道侧按它去重
        request.setIdemKey("LW:91:abcdef");
        request.setCreatedAt(Instant.now());
        return request;
    }

    private LineManager stubLineManager() {
        LineManager manager = new LineManager();
        manager.setManagerId(8L);
        manager.setManagerName("线长8");
        return manager;
    }

    private static LineWithdrawPayoutService.PayoutResult linePaid() {
        return new LineWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok");
    }

    /**
     * 防线 ③：{@code payout()} 闸门必须<b>锁内重查</b>。
     * 第 1 次读（加锁前，只为取 managerId）给 APPROVED，第 2 次读（锁内闸门）给 PAID
     * ⇒ 409，渠道与记账都不许被碰到。本用例是这条修正的回归锁。
     */
    @Test
    void manualPayout_withStaleApprovedSnapshot_isBlockedByInLockReRead() {
        LineWithdrawRequest approved = lineRequest("APPROVED");
        AtomicInteger reads = new AtomicInteger();
        when(withdrawMapper.findById(any())).thenAnswer(invocation -> {
            LineWithdrawRequest row = copyRow(approved);
            row.setStatus(reads.getAndIncrement() == 0 ? "APPROVED" : "PAID");
            return Optional.of(row);
        });
        when(distributedLockService.tryLock(
                LineWithdrawService.lineWalletLockKey(8L), 60L, 5L)).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.payout(1L, 91L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("当前状态不可打款"), ex.getReason());

        verify(payoutService, never()).payout(any(), any(), any());
        verify(lineWalletService, never()).consumeFrozenSplit(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(distributedLockService).unlock(LineWithdrawService.lineWalletLockKey(8L));
    }

    /**
     * 防线 ②：{@code markPaying} 不再允许 {@code PAYING → PAYING} 重入。
     * 陈旧 PAYING 没有合法调用方，正确处置是超时扫描（MOCK 置 FAILED 后走 payout 重试），
     * 而不是跟着再打一次。
     */
    @Test
    void executePayout_onPayingRow_isRejectedWithoutClaimAttempt() {
        wireStatefulTable();
        given(lineRequest("PAYING"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.executePayout(91L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("当前状态不可打款"), ex.getReason());

        verify(withdrawMapper, never()).claimForPayout(anyLong(), anyString(), any());
        verify(payoutService, never()).payout(any(), any(), any());
        verify(lineWalletService, never()).consumeFrozenSplit(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * 防线 ④ 精确打击：表里的真值已是 PAYING，但调用方<b>每次读到的都是旧快照 APPROVED</b>
     * （读副本延迟 / 丢失更新）。此时防线 ③「锁内重查」也放行 —— 状态集合守卫键在
     * 「读到的值」上，读错就守不住。唯一还拦得住的是条件 UPDATE：WHERE 由<b>数据库</b>求值，
     * 抢 0 行即 409。线长单同样没有 {@code @Version}，这一条不能省。
     */
    @Test
    void markPaying_withStaleApprovedRead_losesClaimAndNeverTouchesChannel() {
        given(lineRequest("PAYING"));
        when(withdrawMapper.claimForPayout(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> claimRow(invocation.getArgument(0), invocation.getArgument(1)));
        when(withdrawMapper.findById(any())).thenAnswer(invocation -> {
            Optional<LineWithdrawRequest> fresh = readRow(invocation.getArgument(0));
            assertTrue(fresh.isPresent());
            LineWithdrawRequest staleSnapshot = fresh.get();
            staleSnapshot.setStatus("APPROVED"); // 全程旧快照：锁内重查也看不出已 PAYING
            return Optional.of(staleSnapshot);
        });

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.executePayout(91L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("已有出款在进行中"), ex.getReason());

        verify(payoutService, never()).payout(any(), any(), any());
        verify(lineWalletService, never()).consumeFrozenSplit(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * 坐实的那个窗口：提现锁租约 60s 且<b>无看门狗</b>，渠道 I/O 挂过 60s ⇒ 第二个请求拿到锁。
     * 这里直接把两线程同时推进 {@code executePayout}（等价于锁已失效），要求
     * <b>恰有一方触达渠道</b>、另一方被状态集合或条件 UPDATE 拦下。
     */
    @Test
    void concurrentPayout_withDeadLock_onlyOneCallerReachesChannel() throws Exception {
        wireStatefulTable();
        given(lineRequest("APPROVED"));
        when(lineManagerService.requireManager(8L)).thenReturn(stubLineManager());

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch insideFirstPayout = new CountDownLatch(1);
        CountDownLatch releaseFirstPayout = new CountDownLatch(1);
        when(payoutService.payout(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                insideFirstPayout.countDown();
                assertTrue(releaseFirstPayout.await(5, TimeUnit.SECONDS), "第一笔须停在渠道 I/O 期间");
            }
            return linePaid();
        });

        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicReference<String> statusA = new AtomicReference<>();
        AtomicReference<String> statusB = new AtomicReference<>();
        Thread a = new Thread(() -> runPayout(statusA, errorA));
        a.start();
        assertTrue(insideFirstPayout.await(5, TimeUnit.SECONDS), "第一笔应已抢到出款权并停在渠道 I/O");

        Thread b = new Thread(() -> runPayout(statusB, errorB));
        b.start();
        b.join(10_000);
        releaseFirstPayout.countDown();
        a.join(10_000);

        assertNull(errorA.get(), String.valueOf(errorA.get()));
        assertEquals("PAID", statusA.get());
        assertNotNull(errorB.get(), "第二笔必须被拒");
        assertEquals(1, calls.get(), "同一提现单只能有一方触达渠道");
        verify(lineWalletService, times(1)).consumeFrozenSplit(
                eq(8L), eq(10_000L), eq(0L), eq("WITHDRAW"), eq("91"), anyString());
    }

    private void runPayout(AtomicReference<String> status, AtomicReference<Throwable> error) {
        try {
            status.set(service.executePayout(91L).status());
        } catch (Throwable t) {
            error.set(t);
        }
    }
}
