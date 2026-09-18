package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantWithdrawConcurrencyTest {

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletAccountMapper accountMapper;
    @Mock private MerchantWalletLedgerMapper ledgerMapper;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private MerchantWithdrawPayoutService payoutService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties = new MerchantWithdrawProperties(true, 100, 500_000, 50_000, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, accountMapper, ledgerMapper,
                merchantWalletService, payoutService, properties,
                merchantFeaturePackService, merchantScopeService, permissionService, auditService,
                distributedLockService, null, null);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void apply_whenLockBusy_rejectsWithConflict() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.apply(1L, "M-1", 10_000L, "REQ-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void apply_acquiresLockAndFreezesBalance() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-1");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        AtomicReference<MerchantWithdrawRequest> stored = new AtomicReference<>();
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-2")).thenReturn(Optional.empty());
        when(merchantWalletService.ensureAccount("M-1")).thenReturn(account);
        when(withdrawMapper.sumAmountByMerchantSince(eq("M-1"), any())).thenReturn(0L);
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            MerchantWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(99L);
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(99L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(merchant))).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok"));

        MerchantWithdrawRequestDto dto = service.apply(1L, "M-1", 10_000L, "REQ-2");

        assertEquals("PAID", dto.status());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), any(), any(), any());
        verify(distributedLockService).unlock(MerchantWithdrawService.merchantWalletLockKey("M-1"));
    }

    /** W6(H26): 打款失败即释放冻结，不得 consumeFrozen；CANCELLED 后不再重复打款。 */
    @Test
    void apply_payoutFailed_releasesFrozen_cancelFinalizes() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-1");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        AtomicReference<MerchantWithdrawRequest> stored = new AtomicReference<>();
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-FAIL")).thenReturn(Optional.empty());
        when(merchantWalletService.ensureAccount("M-1")).thenReturn(account);
        when(withdrawMapper.sumAmountByMerchantSince(eq("M-1"), any())).thenReturn(0L);
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            MerchantWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(77L);
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(77L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(merchant))).thenReturn(
                MerchantWithdrawPayoutService.PayoutResult.failure("WECHAT", null, "转账未接入"));

        MerchantWithdrawRequestDto failed = service.apply(1L, "M-1", 10_000L, "REQ-FAIL");

        assertEquals("FAILED", failed.status());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), any(), any(), any());
        // H26：FAILED 落账同事务即解冻
        verify(merchantWalletService).releaseFrozen(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("77"), anyString());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());

        MerchantWithdrawRequestDto cancelled = service.cancelFailed(1L, 77L, "放弃打款");

        assertEquals("CANCELLED", cancelled.status());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    /** H26：从 FAILED 重试打款，markPaying 需重新冻结后再打款。 */
    @Test
    void payoutRetryFromFailed_refreezesBeforePaying() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestId(88L);
        request.setRequestNo("REQ-RETRY");
        request.setMerchantId("M-1");
        request.setAmountCents(10_000L);
        request.setStatus("FAILED");

        when(withdrawMapper.findById(88L)).thenReturn(Optional.of(request));
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);
        when(payoutService.payout(any(), eq(merchant))).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-2", "ok"));

        MerchantWithdrawRequestDto dto = service.payout(1L, 88L);

        assertEquals("PAID", dto.status());
        // FAILED → PAYING 时重新冻结 + PAID 时 consume
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("88"), anyString());
        verify(merchantWalletService).consumeFrozen(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("88"), anyString());
    }

    /** H38：PAYING 超过 1 小时的提现单被兜底置 FAILED 并解冻。 */
    @Test
    void failStalePayingWithdraws_marksFailedAndReleases() {
        MerchantWithdrawRequest stale = new MerchantWithdrawRequest();
        stale.setRequestId(66L);
        stale.setRequestNo("REQ-STALE");
        stale.setMerchantId("M-1");
        stale.setAmountCents(10_000L);
        stale.setStatus("PAYING");

        when(withdrawMapper.findByStatusAndUpdatedAtBefore(eq("PAYING"), any())).thenReturn(List.of(stale));
        when(withdrawMapper.findById(66L)).thenReturn(Optional.of(stale));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);

        assertEquals(1, service.failStalePayingWithdraws());

        assertEquals("FAILED", stale.getStatus());
        verify(merchantWalletService).releaseFrozen(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("66"), anyString());
    }

    /** H53：绑定多个商户且未指定 merchantId 时拒绝申请，显式指定合法商户可提现。 */
    @Test
    void merchantApply_multiBound_requiresExplicitMerchantId() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("M-1", "M-2"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.merchantApply(9L, 10_000L, "REQ-M1", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-2");
        merchant.setMerchantName("商户2");
        when(merchantMapper.findById("M-2")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-2"), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-M2")).thenReturn(Optional.empty());
        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-2");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);
        when(merchantWalletService.ensureAccount("M-2")).thenReturn(account);
        when(withdrawMapper.sumAmountByMerchantSince(eq("M-2"), any())).thenReturn(0L);
        AtomicReference<MerchantWithdrawRequest> stored = new AtomicReference<>();
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            MerchantWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(101L);
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(101L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(merchant))).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-M2", "ok"));

        MerchantWithdrawRequestDto dto = service.merchantApply(9L, 10_000L, "REQ-M2", "M-2");
        assertEquals("PAID", dto.status());
        assertEquals("M-2", dto.merchantId());
    }

    /** H53：显式指定未绑定的商户被拒绝。 */
    @Test
    void merchantApply_explicitForeignMerchant_rejected() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("M-1"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.merchantApply(9L, 10_000L, "REQ-X", "M-9"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
