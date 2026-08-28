package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceLedgerConcurrencyTest {

    @Mock private UserAccountMapper accountRepository;
    @Mock private PaymentOperationMapper operationRepository;
    @Mock private DistributedLockService distributedLockService;

    private BalanceLedgerService service;

    @BeforeEach
    void setUp() {
        service = new BalanceLedgerService(accountRepository, operationRepository, distributedLockService);
    }

    @Test
    void change_whenBalanceLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                BalanceLedgerService.balanceLockKey(42L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.change(42L, 100, "CHARGE", "O-1", "IDEM-1", "test"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
