package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.StaffWalletAccountMapper;
import com.aicabinet.trade.mapper.StaffWalletLedgerMapper;
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

/** 补货员钱包并发基线（对齐 Merchant/Line 两份 concurrency 测试，A 案统一前 pin 住锁语义）。 */
@ExtendWith(MockitoExtension.class)
class StaffWalletConcurrencyTest {

    @Mock private StaffWalletAccountMapper accountMapper;
    @Mock private StaffWalletLedgerMapper ledgerMapper;
    @Mock private DistributedLockService distributedLockService;

    private StaffWalletService service;

    @BeforeEach
    void setUp() {
        service = new StaffWalletService(accountMapper, ledgerMapper, distributedLockService);
    }

    @Test
    void payoutIfAbsent_whenWalletLockBusy_rejectsWithConflictAndTouchesNoLedger() {
        when(distributedLockService.tryLock(
                StaffWalletService.walletLockKey(99L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.payoutIfAbsent(99L, 50L, "WITHDRAW_PAYOUT", "STAFF_WITHDRAW", "req-1", "note"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(accountMapper, ledgerMapper);
    }
}
