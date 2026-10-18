package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.WalletAccount;
import com.aicabinet.trade.domain.WalletLedger;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.WalletAccountMapper;
import com.aicabinet.trade.mapper.WalletLedgerMapper;
import com.aicabinet.trade.service.wallet.WalletDomain;
import com.aicabinet.trade.service.wallet.WalletEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V308 提现手续费拆分记账 —— 现由 {@link WalletEngine}（domain=MERCHANT）承载。
 *
 * <p>核心不变量：余额与冻结合计只扣一次 {@code net + fee}（== 毛额），流水拆 {@code WITHDRAW_PAID -net}
 * + {@code WITHDRAW_FEE -fee} 两行，两行相加==毛额。引擎<b>不再原地改账户对象</b>，而是走
 * {@code updateBalances} + {@code insert}，故断言取「捕获的入参」而非「变异后的 account」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V308 提现手续费拆分记账（WalletEngine）")
class MerchantWalletFeeSplitTest {

    @Mock private WalletAccountMapper accountMapper;
    @Mock private WalletLedgerMapper ledgerMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private DistributedLockService distributedLockService;

    private WalletEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WalletEngine(accountMapper, ledgerMapper, merchantMapper, distributedLockService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        // balance=100000 (¥1000), frozen=10000 (¥100)
        WalletAccount account = account(100_000L, 10_000L);
        lenient().when(accountMapper.findByOwnerRaw("MERCHANT", "M-1")).thenReturn(account);
        lenient().when(accountMapper.findByOwnerForUpdate("MERCHANT", "M-1"))
                .thenReturn(Optional.of(account));
        lenient().when(merchantMapper.selectById("M-1")).thenReturn(null);
    }

    private static WalletAccount account(long balance, long frozen) {
        WalletAccount a = new WalletAccount();
        a.setDomain("MERCHANT");
        a.setOwnerId("M-1");
        a.setBalanceCents(balance);
        a.setFrozenCents(frozen);
        return a;
    }

    @Test
    @DisplayName("有手续费时拆成两行：WITHDRAW_PAID -净额 + WITHDRAW_FEE -手续费，合计==毛额")
    void feePresent_writesTwoLedgerLines() {
        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 9_900L, 100L, "WITHDRAW", "R-1", "提现打款成功");

        ArgumentCaptor<WalletLedger> captor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerMapper, times(2)).insert(captor.capture());
        List<WalletLedger> lines = captor.getAllValues();
        WalletLedger paid = find(lines, "WITHDRAW_PAID");
        WalletLedger fee = find(lines, "WITHDRAW_FEE");

        assertEquals(-9_900L, paid.getAmountCents());
        assertEquals(-100L, fee.getAmountCents());
        assertEquals(-10_000L, paid.getAmountCents() + fee.getAmountCents());
        // 余额/冻结各只扣一次毛额
        verify(accountMapper).updateBalances(eq("MERCHANT"), eq("M-1"), eq(90_000L), eq(0L), any(Instant.class));
    }

    @Test
    @DisplayName("🔴 两行流水金额之和必须等于毛额（对账恒等式）")
    void twoLinesSumEqualsGross() {
        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 9_975L, 25L, "WITHDRAW", "R-2", "ok");
        ArgumentCaptor<WalletLedger> captor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerMapper, times(2)).insert(captor.capture());
        long sum = captor.getAllValues().stream().mapToLong(WalletLedger::getAmountCents).sum();
        assertEquals(-10_000L, sum);
    }

    @Test
    @DisplayName("无手续费时只记一行（不留 0 元流水噪音）")
    void zeroFee_writesSingleLine() {
        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 10_000L, 0L, "WITHDRAW", "R-3", "ok");
        ArgumentCaptor<WalletLedger> captor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerMapper, times(1)).insert(captor.capture());
        assertEquals("WITHDRAW_PAID", captor.getValue().getEntryType());
        assertEquals(-10_000L, captor.getValue().getAmountCents());
        verify(accountMapper).updateBalances(eq("MERCHANT"), eq("M-1"), eq(90_000L), eq(0L), any(Instant.class));
    }

    @Test
    @DisplayName("快照可追溯：WITHDRAW_PAID 行的 balance_after 是「扣手续费前」的值")
    void balanceAfterSnapshot_isTraceable() {
        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 9_900L, 100L, "WITHDRAW", "R-4", "ok");
        ArgumentCaptor<WalletLedger> captor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerMapper, times(2)).insert(captor.capture());
        WalletLedger paid = find(captor.getAllValues(), "WITHDRAW_PAID");
        WalletLedger fee = find(captor.getAllValues(), "WITHDRAW_FEE");
        assertEquals(90_100L, paid.getBalanceAfter());
        assertEquals(90_000L, fee.getBalanceAfter());
    }

    @Test
    @DisplayName("冻结不足 → 抛 412，既不更新余额也不记流水")
    void insufficientFrozen_throwsAndNoLedger() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 19_900L, 100L, "WITHDRAW", "R-6", "ok"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(accountMapper, never()).updateBalances(anyString(), anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("负数手续费按 0 处理（不产生负向扣款/贷记）")
    void negativeFee_treatedAsZero() {
        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 10_000L, -500L, "WITHDRAW", "R-7", "ok");
        ArgumentCaptor<WalletLedger> captor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerMapper, times(1)).insert(captor.capture());
        assertEquals(-10_000L, captor.getValue().getAmountCents());
    }

    @Test
    @DisplayName("净额与手续费都为 0 → 拒绝（金额不合法）")
    void bothZero_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 0L, 0L, "WITHDRAW", "R-8", "ok"));
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
    }

    @Test
    @DisplayName("旧接口 consumeFrozen 行为不变（转发到拆分版，fee=0）")
    void legacyConsumeFrozen_unchanged() {
        engine.consumeFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, "WITHDRAW", "R-9", "ok");
        ArgumentCaptor<WalletLedger> captor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerMapper, times(1)).insert(captor.capture());
        assertEquals(-10_000L, captor.getValue().getAmountCents());
    }

    private static WalletLedger find(List<WalletLedger> lines, String entryType) {
        return lines.stream().filter(l -> entryType.equals(l.getEntryType())).findFirst().orElseThrow();
    }
}
