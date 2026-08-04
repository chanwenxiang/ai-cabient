package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BalanceLedgerServiceTest {
    @Mock UserAccountMapper accountRepository;
    @Mock PaymentOperationMapper operationRepository;
    BalanceLedgerService service;

    @BeforeEach void setUp() { service = new BalanceLedgerService(accountRepository, operationRepository); }

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

    private UserAccount account(Long id, int balance) { UserAccount a = new UserAccount(); a.setUserId(id); a.setBalanceCents(balance); return a; }
}
