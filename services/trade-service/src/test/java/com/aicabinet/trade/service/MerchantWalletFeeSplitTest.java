package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWalletLedger;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V308：提现手续费<b>拆分记账</b>。
 *
 * <p>核心不变量：<b>余额与冻结合计只扣一次 {@code net + fee}</b>（== 原毛额），
 * 但流水拆成 {@code WITHDRAW_PAID -net} + {@code WITHDRAW_FEE -fee} 两行。
 * 两行相加必须等于毛额 —— 这是审计口径，也是「平台没多扣/少扣」的证明。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V308 提现手续费拆分记账")
class MerchantWalletFeeSplitTest {

    @Mock private MerchantWalletAccountMapper accountMapper;
    @Mock private MerchantWalletLedgerMapper ledgerMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private DistributedLockService distributedLockService;

    private MerchantWalletService service;
    private MerchantWalletAccount account;

    @BeforeEach
    void setUp() {
        service = new MerchantWalletService(accountMapper, ledgerMapper, merchantMapper, distributedLockService);
        // 🔴 三个参数必须都是 long 匹配：tryLock(String, long leaseTime, long waitTime)。
        //    这里曾用 anyInt() 写第三参 ⇒ 静默不匹配 ⇒ tryLock 返 false ⇒ 全撞 409 假失败。
        lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);

        // balance = 100000 (¥1000)，frozen = 10000 (¥100)
        account = new MerchantWalletAccount();
        account.setMerchantId("M-1");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(10_000L);
        lenient().when(accountMapper.selectById("M-1")).thenReturn(account);
        lenient().when(accountMapper.findByIdForUpdate("M-1")).thenReturn(Optional.of(account));
        // merchantMapper.selectById 是 MyBatis-Plus 继承方法，返回 Merchant（可能为 null），
        // 不是 Optional —— 这里返回 null 让流水不填 merchantName（测试不关心该字段）
        lenient().when(merchantMapper.selectById("M-1")).thenReturn(null);
    }

    @Test
    @DisplayName("有手续费时拆成两行：WITHDRAW_PAID -净额 + WITHDRAW_FEE -手续费")
    void feePresent_writesTwoLedgerLines() {
        service.consumeFrozenSplit("M-1", 9_900L, 100L, "WITHDRAW", "R-1", "提现打款成功");

        ArgumentCaptor<MerchantWalletLedger> captor = ArgumentCaptor.forClass(MerchantWalletLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<MerchantWalletLedger> lines = captor.getAllValues();

        MerchantWalletLedger paid = lines.stream()
                .filter(l -> "WITHDRAW_PAID".equals(l.getEntryType())).findFirst().orElseThrow();
        MerchantWalletLedger fee = lines.stream()
                .filter(l -> "WITHDRAW_FEE".equals(l.getEntryType())).findFirst().orElseThrow();

        assertEquals(-9_900L, paid.getAmountCents());
        assertEquals(-100L, fee.getAmountCents());
        // 🔴 核心不变量：两行相加 == 毛额（不多扣也不少扣）
        assertEquals(-10_000L, paid.getAmountCents() + fee.getAmountCents());

        // 余额与冻结各只扣一次毛额
        assertEquals(90_000L, account.getBalanceCents());
        assertEquals(0L, account.getFrozenCents());
    }

    @Test
    @DisplayName("🔴 两行流水金额之和必须等于毛额（对账恒等式）")
    void twoLinesSumEqualsGross() {
        service.consumeFrozenSplit("M-1", 9_975L, 25L, "WITHDRAW", "R-2", "ok");
        ArgumentCaptor<MerchantWalletLedger> captor = ArgumentCaptor.forClass(MerchantWalletLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        long sum = captor.getAllValues().stream().mapToLong(MerchantWalletLedger::getAmountCents).sum();
        assertEquals(-10_000L, sum);
        assertEquals(90_000L - 0L, account.getBalanceCents());
    }

    @Test
    @DisplayName("无手续费时只记一行（不留0 元流水噪音），与改动前行为完全一致")
    void zeroFee_writesSingleLine() {
        service.consumeFrozenSplit("M-1", 10_000L, 0L, "WITHDRAW", "R-3", "ok");

        ArgumentCaptor<MerchantWalletLedger> captor = ArgumentCaptor.forClass(MerchantWalletLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(1)).insert(captor.capture());
        MerchantWalletLedger only = captor.getValue();
        assertEquals("WITHDRAW_PAID", only.getEntryType());
        assertEquals(-10_000L, only.getAmountCents());
        assertEquals(90_000L, account.getBalanceCents());
        assertEquals(0L, account.getFrozenCents());
    }

    @Test
    @DisplayName("快照可追溯：WITHDRAW_PAID 行的balance_after 是「扣手续费前」的值")
    void balanceAfterSnapshot_isTraceable() {
        service.consumeFrozenSplit("M-1", 9_900L, 100L, "WITHDRAW", "R-4", "ok");
        ArgumentCaptor<MerchantWalletLedger> captor = ArgumentCaptor.forClass(MerchantWalletLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(2)).insert(captor.capture());

        MerchantWalletLedger paid = captor.getAllValues().stream()
                .filter(l -> "WITHDRAW_PAID".equals(l.getEntryType())).findFirst().orElseThrow();
        MerchantWalletLedger fee = captor.getAllValues().stream()
                .filter(l -> "WITHDRAW_FEE".equals(l.getEntryType())).findFirst().orElseThrow();

        // 净额行的快照 = 最终余额 + 手续费 = 90000 + 100 = 90100 ⇒ 两行连起来是完整轨迹
        assertEquals(90_100L, paid.getBalanceAfter());
        assertEquals(90_000L, fee.getBalanceAfter());
    }

    @Test
    @DisplayName("冻结不足 → 抛 412 且流水为空")
    void insufficientFrozen_throwsAndNoLedger() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.consumeFrozenSplit("M-1", 19_900L, 100L, "WITHDRAW", "R-6", "ok"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
        verify(ledgerMapper, never()).insert(any(MerchantWalletLedger.class));
        // 余额未被改动
        assertEquals(100_000L, account.getBalanceCents());
        assertEquals(10_000L, account.getFrozenCents());
    }

    @Test
    @DisplayName("负数手续费按 0 处理（不产生负向扣款/贷记）")
    void negativeFee_treatedAsZero() {
        service.consumeFrozenSplit("M-1", 10_000L, -500L, "WITHDRAW", "R-7", "ok");
        ArgumentCaptor<MerchantWalletLedger> captor = ArgumentCaptor.forClass(MerchantWalletLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(1)).insert(captor.capture());
        assertEquals(-10_000L, captor.getValue().getAmountCents());
        assertEquals(90_000L, account.getBalanceCents());
    }

    @Test
    @DisplayName("净额与手续费都为 0 → 拒绝（金额不合法）")
    void bothZero_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.consumeFrozenSplit("M-1", 0L, 0L, "WITHDRAW", "R-8", "ok"));
        verify(ledgerMapper, never()).insert(any(MerchantWalletLedger.class));
    }

    @Test
    @DisplayName("旧接口 consumeFrozen 行为不变（转发到拆分版，fee=0）")
    void legacyConsumeFrozen_unchanged() {
        service.consumeFrozen("M-1", 10_000L, "WITHDRAW", "R-9", "ok");
        ArgumentCaptor<MerchantWalletLedger> captor = ArgumentCaptor.forClass(MerchantWalletLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(1)).insert(captor.capture());
        assertEquals("WITHDRAW_PAID", captor.getValue().getEntryType());
        assertEquals(-10_000L, captor.getValue().getAmountCents());
        assertEquals(90_000L, account.getBalanceCents());
        assertTrue(captor.getValue().getEntryType().equals("WITHDRAW_PAID"));
    }
}