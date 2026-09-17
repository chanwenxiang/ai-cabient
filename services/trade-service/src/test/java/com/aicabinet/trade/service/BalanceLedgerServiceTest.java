package com.aicabinet.trade.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BalanceLedgerServiceTest {
    @Mock UserAccountMapper accountRepository;
    @Mock PaymentOperationMapper operationRepository;
    @Mock DistributedLockService distributedLockService;
    BalanceLedgerService service;

    @BeforeEach void setUp() {
        // 真实 TransactionTemplate + mock 事务管理器：callback 原样执行，无真实连接
        service = new BalanceLedgerService(accountRepository, operationRepository, distributedLockService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        // lenient：仅 change() 路径需要余额锁；list() 路径不涉及
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    @Test void debit_recordsBeforeAndAfterBalance() {
        UserAccount account = account(7L, 1000);
        when(operationRepository.findByIdempotencyKey("charge-1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(account));
        when(operationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        PaymentOperation result = service.change(7L, -350, "CHARGE", "O1", "charge-1", "order charge");
        assertEquals(650, account.getBalanceCents());
        assertEquals(1000, result.getBalanceBeforeCents());
        assertEquals(650, result.getBalanceAfterCents());
        assertEquals(7L, result.getUserId());
    }

    @Test void debit_neverAllowsNegativeBalance() {
        when(operationRepository.findByIdempotencyKey("charge-1")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(account(7L, 100)));
        assertThrows(ResponseStatusException.class,
                () -> service.change(7L, -101, "CHARGE", "O1", "charge-1", "order charge"));
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test void repeatedIdempotencyKey_returnsOriginalWithoutChangingBalance() {
        PaymentOperation original = new PaymentOperation(); original.setIdempotencyKey("charge-1");
        when(operationRepository.findByIdempotencyKey("charge-1")).thenReturn(Optional.of(original));
        assertSame(original, service.change(7L, -100, "CHARGE", "O1", "charge-1", "order charge"));
        verifyNoInteractions(accountRepository);
    }

    // ---- W-4：余额明细的「带符号金额」映射 ----
    // 纯冻结/释放类只改冻结额，可用余额前后一致；若沿用余额差算法会全部显示 ¥0.00。

    @Test void list_pureFreeze_amountsFromOperationNotZero() {
        stubTransactions(freeze("PREAUTH_FREEZE", 5000, 50000, 50000));
        assertEquals(-5000, firstAmount());
    }

    @Test void list_pureRelease_amountsAsInflow() {
        stubTransactions(freeze("PREAUTH_RELEASE", 5000, 50000, 50000));
        assertEquals(5000, firstAmount());
    }

    @Test void list_refundFreeze_amountsAsOutflow() {
        stubTransactions(freeze("BALANCE_REFUND_FREEZE", 3000, 8000, 8000));
        assertEquals(-3000, firstAmount());
    }

    @Test void list_refundRelease_amountsAsInflow() {
        stubTransactions(freeze("BALANCE_REFUND_RELEASE", 3000, 8000, 8000));
        assertEquals(3000, firstAmount());
    }

    @Test void list_capture_usesBalanceDeltaNotOperationAmount() {
        // 冲抵真扣可用余额：50000 -> 47000；应以余额差为准（-3000），而非按类型猜方向
        stubTransactions(freeze("PREAUTH_CAPTURE", 3000, 50000, 47000));
        assertEquals(-3000, firstAmount());
    }

    @Test void list_charge_usesBalanceDelta() {
        stubTransactions(freeze("CHARGE", 350, 1000, 650));
        assertEquals(-350, firstAmount());
    }

    @Test void list_recharge_usesBalanceDelta() {
        stubTransactions(freeze("RECHARGE", 10000, 0, 10000));
        assertEquals(10000, firstAmount());
    }

    @Test void list_unknownZeroDeltaType_staysZeroWithoutGuessingDirection() {
        // 未知类型且余额未变：不得臆测方向，维持 0
        stubTransactions(freeze("SOME_FUTURE_TYPE", 100, 500, 500));
        assertEquals(0, firstAmount());
    }

    @Test void list_missingBalanceSnapshot_fallsBackToTypeSign() {
        stubTransactions(freeze("CHARGE", 350, null, null));
        assertEquals(-350, firstAmount());
    }

    private void stubTransactions(PaymentOperation operation) {
        when(operationRepository.findByUserIdOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(operation)));
    }

    private int firstAmount() {
        return service.list(7L, 0, 20).items().get(0).amountCents();
    }

    private PaymentOperation freeze(String type, int amountCents, Integer before, Integer after) {
        PaymentOperation op = new PaymentOperation();
        op.setOperationId("op-" + type);
        op.setUserId(7L);
        op.setOperationType(type);
        op.setAmountCents(amountCents);
        op.setBalanceBeforeCents(before);
        op.setBalanceAfterCents(after);
        return op;
    }

    private UserAccount account(Long id, int balance) { UserAccount a = new UserAccount(); a.setUserId(id); a.setBalanceCents(balance); return a; }
}
