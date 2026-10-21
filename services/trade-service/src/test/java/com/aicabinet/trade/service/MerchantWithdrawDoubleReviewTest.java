package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「双人同时审核/打款同一张提现单」能否重复出款 —— 坐实 + 回归锁。
 *
 * <p>本类的读路径用一张<b>有状态的假表</b>（{@link #table}）替身：每次 {@code findById}
 * 返回行副本、{@code updateById / claimForPayout} 按条件写回副本，因此服务在锁内重查时读到的
 * 是<b>已提交</b>状态，与真库 READ COMMITTED 同构 —— 这正是要坐实的那条守卫，
 * 用固定返回值 mock 是测不出来的。
 *
 * <p>四层守卫逐条有例：
 * ① 审核+出款整段在商户钱包分布式锁内（拿不到就 409，fail-loud）；
 * ② 审核闸门在<b>锁内重查</b>，非 PENDING_REVIEW 直接 409；
 * ③ {@code payout()} 闸门同样锁内重查（对齐 cancelFailed 的 AU-007 修正）；
 * ④ 出款权用<b>条件 UPDATE</b> 抢占（{@code claimForPayout}）：锁租约到期、
 *    或调用方读到旧快照时，抢 0 行即 409 —— 单据没有 {@code @Version}，
 *    这一条把资金防线从「只剩渠道幂等键」拉回本地可控。
 *
 * <p>🔴 诚实边界：单测无 Spring 事务代理，{@code @Transactional} 不生效，
 * 所以这里证明的是「锁 + 锁内重查 + 条件 UPDATE + 状态机」的<b>代码语义</b>，
 * 不是真库隔离级别；真库并发复核见 F1 驱动器复跑。
 */
@ExtendWith(MockitoExtension.class)
class MerchantWithdrawDoubleReviewTest {

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
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;

    private final Map<Long, MerchantWithdrawRequest> table = new HashMap<>();
    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(true, 100, 0L, 500_000, 50_000, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper,
                merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper,
                merchantFeaturePackService, merchantScopeService, permissionService, auditService,
                distributedLockService, approvalWorkflowService,
                WithdrawPolicyResolver.ymlOnly(properties),
                payoutChannelRegistry, orderRevenueSplitMapper, withdrawEligibilityService,
                null, null, null);
        ReflectionTestUtils.setField(service, "self", service);

        lenient().when(withdrawMapper.findById(any())).thenAnswer(invocation -> readRow(invocation.getArgument(0)));
        lenient().when(withdrawMapper.updateById(any(MerchantWithdrawRequest.class)))
                .thenAnswer(invocation -> {
                    MerchantWithdrawRequest row = invocation.getArgument(0);
                    synchronized (table) {
                        table.put(row.getRequestId(), copy(row));
                    }
                    return 1;
                });
        lenient().when(merchantMapper.findById("M-1")).thenAnswer(invocation -> Optional.of(stubMerchant()));
        lenient().when(payoutAccountMapper.findByAccountId(1L))
                .thenReturn(Optional.of(stubPayeeAccount()));
        lenient().when(approvalWorkflowService.isInstanceApproved(
                eq("MERCHANT_WITHDRAW"), eq("91"))).thenReturn(true);
        // 条件 UPDATE 的语义在假表里如实实现：只有当前状态仍等于 from 才迁移到 PAYING
        lenient().when(withdrawMapper.claimForPayout(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> claimRow(
                        invocation.getArgument(0), invocation.getArgument(1)));
    }

    // ============ 假表 ============

    /** 模拟 {@code UPDATE … SET status='PAYING' WHERE request_id=? AND status=?} 的返回行数。 */
    private int claimRow(Long requestId, String fromStatus) {
        synchronized (table) {
            MerchantWithdrawRequest row = table.get(requestId);
            if (row == null || !fromStatus.equals(row.getStatus())) {
                return 0;
            }
            row.setStatus("PAYING");
            row.setUpdatedAt(Instant.now());
            return 1;
        }
    }

    private Optional<MerchantWithdrawRequest> readRow(Long requestId) {
        synchronized (table) {
            MerchantWithdrawRequest row = table.get(requestId);
            return row == null ? Optional.empty() : Optional.of(copy(row));
        }
    }

    private static MerchantWithdrawRequest copy(MerchantWithdrawRequest src) {
        MerchantWithdrawRequest dst = new MerchantWithdrawRequest();
        dst.setRequestId(src.getRequestId());
        dst.setRequestNo(src.getRequestNo());
        dst.setMerchantId(src.getMerchantId());
        dst.setMerchantName(src.getMerchantName());
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
        dst.setIdemKey(src.getIdemKey());
        dst.setChannelOrderNo(src.getChannelOrderNo());
        dst.setChannelBatchNo(src.getChannelBatchNo());
        return dst;
    }

    /** 把单据放进假表。 */
    private void given(MerchantWithdrawRequest request) {
        synchronized (table) {
            table.put(request.getRequestId(), copy(request));
        }
    }

    private MerchantWithdrawRequest pendingRequest() {
        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestId(91L);
        request.setRequestNo("REQ-91");
        request.setMerchantId("M-1");
        request.setAmountCents(10_000L);
        request.setFeeCents(0L);
        request.setStatus("PENDING_REVIEW");
        request.setPayoutAccountId(1L);
        // 申请时写入的幂等键：重试不换键 ⇒ 渠道侧按它去重
        request.setIdemKey("MW:91:abcdef");
        request.setCreatedAt(Instant.now());
        return request;
    }

    private static PayoutAccount stubPayeeAccount() {
        PayoutAccount account = new PayoutAccount();
        account.setAccountId(1L);
        account.setOwnerType(PayoutConstants.PAYEE_OWNER_MERCHANT);
        account.setAccountType(PayoutConstants.PAYEE_TYPE_COMPANY);
        account.setChannel(PayoutConstants.PAY_CHANNEL_BANK);
        account.setAccountName("测试商户有限公司");
        account.setAccountNoMask("****0123");
        account.setStatus("ACTIVE");
        account.setIsDefault(true);
        return account;
    }

    private static Merchant stubMerchant() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");
        return merchant;
    }

    private static MerchantWithdrawPayoutService.PayoutResult paid() {
        return new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok");
    }

    // ============ ① 状态机守卫：审核提交后再审核必 409 ============

    @Test
    void review_afterFirstReviewCommitted_isRejectedAndPaysExactlyOnce() {
        given(pendingRequest());
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L)).thenReturn(true);
        when(payoutService.payout(any(), any(), any())).thenReturn(paid());

        MerchantWithdrawRequestDto first = service.review(1L, 91L, true, "审核通过");
        assertEquals("PAID", first.status());

        ResponseStatusException second = assertThrows(ResponseStatusException.class,
                () -> service.review(2L, 91L, true, "审核通过"));
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertTrue(second.getReason().contains("当前状态不可审核"), second.getReason());

        verify(payoutService, times(1)).payout(any(), any(), any());
        verify(merchantWalletService, times(1)).consumeFrozenSplit(
                eq("M-1"), eq(10_000L), eq(0L), eq("WITHDRAW"), eq("91"), anyString());
        verify(merchantWalletService, never()).releaseFrozen(
                anyString(), anyLong(), anyString(), anyString(), anyString());
        // 锁必须成对释放：第二笔被状态机拒掉后不能把锁漏给下一位（漏锁会表现为整商户卡死）
        verify(distributedLockService, times(2)).unlock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"));
    }

    // ============ ② 真并发：一把真实互斥锁替身驱动 tryLock/unlock ============

    @Test
    void concurrentReviews_exactlyOnePaysAndOneIsRejected() throws Exception {
        given(pendingRequest());
        String lockKey = MerchantWithdrawService.merchantWalletLockKey("M-1");
        ReentrantLock realLock = new ReentrantLock();
        AtomicInteger payouts = new AtomicInteger();

        doAnswer(invocation -> realLock.tryLock(5, TimeUnit.SECONDS))
                .when(distributedLockService).tryLock(eq(lockKey), eq(60L), eq(5L));
        doAnswer(invocation -> {
            if (realLock.isHeldByCurrentThread()) {
                realLock.unlock();
            }
            return null;
        }).when(distributedLockService).unlock(eq(lockKey));
        when(payoutService.payout(any(), any(), any())).thenAnswer(invocation -> {
            payouts.incrementAndGet();
            // 拉长临界区：让第二线程要么等锁、要么拿到锁后读到已提交状态
            Thread.sleep(200);
            return paid();
        });

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicReference<String> statusA = new AtomicReference<>();
        AtomicReference<String> statusB = new AtomicReference<>();
        Thread a = new Thread(() -> runReview(start, errorA, statusA));
        Thread b = new Thread(() -> runReview(start, errorB, statusB));
        a.start();
        b.start();
        start.countDown();
        a.join(10_000);
        b.join(10_000);

        assertEquals(1, payouts.get(), "同一提现单只能向渠道发起一次出款");
        assertEquals(1, (errorA.get() == null ? 0 : 1) + (errorB.get() == null ? 0 : 1),
                "恰有一笔审核成功，另一笔必须被拒");
        String ok = errorA.get() == null ? statusA.get() : statusB.get();
        assertEquals("PAID", ok);
        verify(merchantWalletService, times(1)).consumeFrozenSplit(
                eq("M-1"), eq(10_000L), eq(0L), eq("WITHDRAW"), eq("91"), anyString());
    }

    private void runReview(CountDownLatch start, AtomicReference<Throwable> error,
                           AtomicReference<String> status) {
        try {
            start.await();
            MerchantWithdrawRequestDto dto = service.review(1L, 91L, true, "审核通过");
            status.set(dto.status());
        } catch (Throwable t) {
            error.set(t);
        }
    }

    // ============ ③ payout() 闸门改为锁内重查（对齐 cancelFailed 的 AU-007）============

    /**
     * {@code payout()} 原先用<b>加锁前</b>读的快照判状态，与 {@code cancelFailed} 的 AU-007
     * 修正不同形 —— 等锁期间并发方已把单据推到 PAID 时，旧快照仍能通过闸门。
     * 现在闸门读的是锁内重查：第 1 次读（加锁前，只为取 merchantId）给 APPROVED，
     * 第 2 次读（锁内闸门）给 PAID ⇒ 409，事务体与渠道都不会被碰到。本用例是这条修正的回归锁。
     */
    @Test
    void manualPayout_withStaleApprovedSnapshot_isBlockedByInLockReRead() {
        MerchantWithdrawRequest approved = pendingRequest();
        approved.setStatus("APPROVED");
        AtomicInteger reads = new AtomicInteger();
        when(withdrawMapper.findById(any())).thenAnswer(invocation -> {
            MerchantWithdrawRequest row = copy(approved);
            // 第 1 次读 = 锁外快照（APPROVED），之后 = 锁内重查（并发方已置 PAID）
            row.setStatus(reads.getAndIncrement() == 0 ? "APPROVED" : "PAID");
            return Optional.of(row);
        });
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.payout(1L, 91L));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("当前状态不可打款"), error.getReason());

        verify(payoutService, never()).payout(any(), any(), any());
        verify(merchantWalletService, never()).consumeFrozenSplit(
                anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(distributedLockService).unlock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"));
    }

    // ============ ④ 出款权条件 UPDATE：锁失效（租约到期）时也只有一方能触达渠道 ============

    /**
     * 坐实过的那个窗口：提现锁租约 60s 且<b>无看门狗</b>（DistributedLockService.java:49），
     * 渠道 I/O 挂过 60s ⇒ 第二个请求能拿到锁；单据没有 {@code @Version}，
     * 「读状态判可打 → updateById 置 PAYING」在这两步之间有丢失更新窗口。
     *
     * <p>修正（本用例即其回归锁）：{@code markPaying} 不再允许 {@code PAYING → PAYING} 重入，
     * 且用 {@code UPDATE … SET status='PAYING' WHERE request_id=? AND status=<读到的旧状态>}
     * 抢出款权（{@code MerchantWithdrawRequestMapper.claimForPayout}）——
     * 抢不到 0 行就 409，绝不触达渠道。资金防线从「只剩渠道幂等键」回到本地可控。
     */
    @Test
    void concurrentPayout_withDeadLock_onlyOneCallerReachesChannel() throws Exception {
        MerchantWithdrawRequest approved = pendingRequest();
        approved.setStatus("APPROVED");
        given(approved);

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch insideFirstPayout = new CountDownLatch(1);
        CountDownLatch releaseFirstPayout = new CountDownLatch(1);
        when(payoutService.payout(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                insideFirstPayout.countDown();
                assertTrue(releaseFirstPayout.await(5, TimeUnit.SECONDS), "第一笔须停在渠道 I/O 期间");
            }
            return paid();
        });

        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicReference<String> statusA = new AtomicReference<>();
        AtomicReference<String> statusB = new AtomicReference<>();
        Thread a = new Thread(() -> runPayout(statusA, errorA));
        a.start();
        assertTrue(insideFirstPayout.await(5, TimeUnit.SECONDS), "第一笔应已抢到出款权并停在渠道 I/O");

        // 第二个请求此刻进来（等价于锁租约已到期）：无论它读到 APPROVED 还是 PAYING，
        // 要么被状态集合拦下，要么在条件 UPDATE 上抢 0 行 —— 两种都不得触达渠道
        Thread b = new Thread(() -> runPayout(statusB, errorB));
        b.start();
        b.join(10_000);
        releaseFirstPayout.countDown();
        a.join(10_000);

        assertNull(errorA.get(), String.valueOf(errorA.get()));
        assertEquals("PAID", statusA.get());
        assertNotNull(errorB.get(), "第二笔必须被拒");
        assertEquals(1, calls.get(), "同一提现单只能有一方触达渠道");
        verify(merchantWalletService, times(1)).consumeFrozenSplit(
                eq("M-1"), eq(10_000L), eq(0L), eq("WITHDRAW"), eq("91"), anyString());
    }

    private void runPayout(AtomicReference<String> status, AtomicReference<Throwable> error) {
        try {
            status.set(service.executePayout(91L).status());
        } catch (Throwable t) {
            error.set(t);
        }
    }

    /**
     * 精确打条件 UPDATE 那一步：表里的真值已是 PAYING，但调用方<b>每次读到的都是旧快照 APPROVED</b>
     * （读副本延迟 / 丢失更新）。此时防线 ③「锁内重查」也会放行 —— 状态集合守卫键在
     * 「读到的值」上，读错就守不住。唯一还拦得住的是防线 ④：条件 UPDATE 的 WHERE 由
     * <b>数据库</b>求值，抢 0 行即 409。单据没有 {@code @Version}，这一条不能省。
     */
    @Test
    void markPaying_withStaleApprovedRead_losesClaimAndNeverTouchesChannel() {
        MerchantWithdrawRequest paying = pendingRequest();
        paying.setStatus("PAYING"); // 并发方已抢到出款权（假表里的真值）
        given(paying);
        when(withdrawMapper.findById(any())).thenAnswer(invocation -> {
            Optional<MerchantWithdrawRequest> fresh = readRow(invocation.getArgument(0));
            assertTrue(fresh.isPresent());
            MerchantWithdrawRequest staleSnapshot = fresh.get();
            staleSnapshot.setStatus("APPROVED"); // 全程旧快照：锁内重查也看不出已 PAYING
            return Optional.of(staleSnapshot);
        });

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.executePayout(91L));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("已有出款在进行中"), error.getReason());

        verify(payoutService, never()).payout(any(), any(), any());
        verify(merchantWalletService, never()).consumeFrozen(
                anyString(), anyLong(), anyString(), anyString(), anyString());
    }
}
