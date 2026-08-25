package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.LineWalletAccountMapper;
import com.aicabinet.trade.mapper.LineWalletLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineWalletConcurrencyTest {

    @Mock private LineWalletAccountMapper accountMapper;
    @Mock private LineWalletLedgerMapper ledgerMapper;
    @Mock private DistributedLockService distributedLockService;

    private LineWalletService service;

    @BeforeEach
    void setUp() {
        service = new LineWalletService(accountMapper, ledgerMapper, distributedLockService);
    }

    @Test
    void credit_whenWalletLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(LineWalletService.walletLockKey(99L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.credit(99L, 100L, "ADJUST", "TEST", "R-1", "note"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
