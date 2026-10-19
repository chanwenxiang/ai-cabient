package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.WalletAccountMapper;
import com.aicabinet.trade.mapper.WalletLedgerMapper;
import com.aicabinet.trade.service.wallet.WalletDomain;
import com.aicabinet.trade.service.wallet.WalletEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantWalletConcurrencyTest {

    @Mock private WalletAccountMapper accountMapper;
    @Mock private WalletLedgerMapper ledgerMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private DistributedLockService distributedLockService;

    private WalletEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WalletEngine(accountMapper, ledgerMapper, merchantMapper, distributedLockService);
    }

    @Test
    void debitIfAbsent_whenWalletLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                WalletDomain.MERCHANT.lockKey("M-LOCK-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> engine.debitIfAbsent(WalletDomain.MERCHANT, "M-LOCK-1", 50L, "DEBIT", "ORDER", "O-1", "note"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(accountMapper, ledgerMapper, merchantMapper);
    }
}
