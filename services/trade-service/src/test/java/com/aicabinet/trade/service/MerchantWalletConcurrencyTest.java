package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
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
class MerchantWalletConcurrencyTest {

    @Mock private MerchantWalletAccountMapper accountMapper;
    @Mock private MerchantWalletLedgerMapper ledgerMapper;
    @Mock private DistributedLockService distributedLockService;

    private MerchantWalletService service;

    @BeforeEach
    void setUp() {
        service = new MerchantWalletService(accountMapper, ledgerMapper, null, distributedLockService);
    }

    @Test
    void debitIfAbsent_whenWalletLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantWalletService.walletLockKey("M-LOCK-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.debitIfAbsent("M-LOCK-1", 50L, "DEBIT", "ORDER", "O-1", "note"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
