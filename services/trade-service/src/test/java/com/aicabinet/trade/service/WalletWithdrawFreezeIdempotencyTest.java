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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * MA-262（R23Q-01，P1）提现冻结生命周期幂等 —— {@link WalletEngine} 汇聚点判据。
 *
 * <p>缺陷形态（真栈可复现）：打款失败落 FAILED 时已经 {@code releaseFrozen} 一次；运营再点
 * 「打款失败取消」（{@code cancelFailed}，只校验状态=FAILED）又释放一次 ⇒ 本单冻结早为 0，
 * 第二次减掉的是<b>另一张在途单</b>的冻结额；那张单打款成功时 {@code consumeFrozenSplit}
 * 冻结不足 ⇒ 回滚，而渠道已出账（钱出账、余额未扣）。单据状态挡不住重复：FAILED 不区分
 * 「已释放」与「已重新冻结」。
 *
 * <p>本测试用<b>真实流水账</b>（内存 ledger + 账户余额随 updateBalances 变化）验证，而不是
 * 让 mock 一律返回 0：幂等判据的输入恰恰是流水，mock 掉流水等于测了个假的通过。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MA-262 提现冻结生命周期：二次解冻幂等 + 不吃他单冻结")
class WalletWithdrawFreezeIdempotencyTest {

    private static final String REF_TYPE = "WITHDRAW";

    @Mock private WalletAccountMapper accountMapper;
    @Mock private WalletLedgerMapper ledgerMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private DistributedLockService distributedLockService;

    private WalletEngine engine;
    private final List<WalletLedger> ledger = new ArrayList<>();
    private long balanceCents = 100_000L;
    private long frozenCents;

    @BeforeEach
    void setUp() {
        engine = new WalletEngine(accountMapper, ledgerMapper, merchantMapper, distributedLockService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(accountMapper.findByOwnerRaw(anyString(), anyString()))
                .thenAnswer(inv -> account());
        lenient().when(accountMapper.findByOwnerForUpdate(anyString(), anyString()))
                .thenAnswer(inv -> Optional.of(account()));
        lenient().when(accountMapper.updateBalances(anyString(), anyString(), any(), any(), any(Instant.class)))
                .thenAnswer(inv -> {
                    balanceCents = inv.getArgument(2);
                    frozenCents = inv.getArgument(3);
                    return 1;
                });
        lenient().when(ledgerMapper.insert(any(WalletLedger.class))).thenAnswer(inv -> {
            ledger.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(ledgerMapper.summarizeFrozenByRef(
                        anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    String refType = inv.getArgument(2);
                    String refId = inv.getArgument(3);
                    Collection<String> types = inv.getArgument(4);
                    List<WalletLedger> hits = ledger.stream()
                            .filter(l -> refType.equals(l.getRefType()) && refId.equals(l.getRefId()))
                            .filter(l -> types.contains(l.getEntryType()))
                            .toList();
                    long sum = hits.stream().mapToLong(l -> l.getAmountCents() == null ? 0L : l.getAmountCents()).sum();
                    return new WalletLedgerMapper.FrozenSummary(hits.size(), sum);
                });
        lenient().when(merchantMapper.selectById(anyString())).thenReturn(null);
    }

    private WalletAccount account() {
        WalletAccount a = new WalletAccount();
        a.setDomain(WalletDomain.MERCHANT.column());
        a.setOwnerId("M-1");
        a.setBalanceCents(balanceCents);
        a.setFrozenCents(frozenCents);
        return a;
    }

    private long ledgerSum(String entryType) {
        return ledger.stream()
                .filter(l -> entryType.equals(l.getEntryType()))
                .mapToLong(WalletLedger::getAmountCents)
                .sum();
    }

    private long ledgerCount(String entryType) {
        return ledger.stream().filter(l -> entryType.equals(l.getEntryType())).count();
    }

    @Test
    @DisplayName("🔴 二次解冻幂等：FAILED 已释放 → 取消再释放，冻结不再减、不记第二行流水")
    void doubleRelease_isIdempotent() {
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "77", "申请冻结");
        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "77", "打款失败释放");
        assertEquals(0L, frozenCents, "首次释放后冻结应清零");

        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "77", "失败取消解冻");

        assertEquals(0L, frozenCents, "第二次释放必须是空转，冻结不得变负");
        assertEquals(1L, ledgerCount("WITHDRAW_RELEASE"), "同一单只应有 1 行释放流水");
        assertEquals(10_000L, ledgerSum("WITHDRAW_RELEASE"));
    }

    @Test
    @DisplayName("🔴 不吃他单冻结：A 单重复解冻后，B 单冻结额仍在，B 打款成功可正常扣款")
    void secondRelease_doesNotEatOtherOrderFreeze() {
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "A 冻结");
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 5_000L, REF_TYPE, "B", "B 冻结");
        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "A 失败释放");
        assertEquals(5_000L, frozenCents);

        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "A 取消再解冻");

        assertEquals(5_000L, frozenCents, "A 的第二次解冻不得吃掉 B 的冻结");
        // B 打款成功：冻结与余额各扣 5000，不因 A 的重复解冻而回滚
        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 5_000L, 0L, REF_TYPE, "B", "B 打款成功");
        assertEquals(0L, frozenCents);
        assertEquals(95_000L, balanceCents);
    }

    @Test
    @DisplayName("🔴 本单冻结已释放时拒绝扣款（412）——账户冻结合计够也不许吃别人的")
    void consume_whenOwnFreezeGone_isRejected() {
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "A 冻结");
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 20_000L, REF_TYPE, "B", "B 冻结");
        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "A 释放");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 10_000L, 0L, REF_TYPE, "A", "A 打款成功"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
        assertEquals(20_000L, frozenCents, "拒绝扣款后 B 的冻结必须原封不动（账户合计≥应付也不能放行）");
        assertEquals(0L, ledgerSum("WITHDRAW_PAID"), "失败路径不得留下扣款流水");
    }

    @Test
    @DisplayName("FAILED 重试打款路径：重新冻结后扣款正常（幂等判据不得误伤重试）")
    void refreezeAfterFailed_thenConsumeWorks() {
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "首次冻结");
        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "失败释放");
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "重试重新冻结");

        engine.consumeFrozenSplit(WalletDomain.MERCHANT, "M-1", 10_000L, 0L, REF_TYPE, "A", "打款成功");

        assertEquals(0L, frozenCents);
        assertEquals(90_000L, balanceCents);
    }

    @Test
    @DisplayName("占用额只剩一半时只释放剩余部分（历史脏数据不越界、不滞留）")
    void release_clampsToHeldAmount() {
        engine.freezeForWithdraw(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "冻结");
        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 4_000L, REF_TYPE, "A", "部分释放");
        assertEquals(6_000L, frozenCents);

        engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 10_000L, REF_TYPE, "A", "再次释放");

        assertEquals(0L, frozenCents, "只能释放本单剩余的 6000");
        assertEquals(10_000L, ledgerSum("WITHDRAW_RELEASE"), "释放流水合计应等于冻结额");
    }

    @Test
    @DisplayName("无冻结流水（迁移前老单据）⇒ 退回账户级旧口径，不静默空转")
    void noLedgerRows_fallsBackToAccountLevel() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> engine.releaseFrozen(WalletDomain.MERCHANT, "M-1", 1_000L, REF_TYPE, "LEGACY", "释放"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode(),
                "账户冻结为 0 时应按旧口径报冻结不足，而不是当作已释放");
        assertEquals(0L, ledgerCount("WITHDRAW_RELEASE"));
    }
}
