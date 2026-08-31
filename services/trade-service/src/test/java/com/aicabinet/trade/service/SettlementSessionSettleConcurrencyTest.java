package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementSessionSettleConcurrencyTest {

    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private DistributedLockService distributedLockService;

    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                sessionRepository, null, orderRepository, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(settlementService, "self", settlementService);
    }

    @Test
    void settle_whenLockBusy_rejectsWithConflict() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-LOCK-1");
        session.setDeviceId("CAB-001");
        when(distributedLockService.tryLock(
                SettlementService.sessionSettleLockKey("S-LOCK-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> settlementService.settle(session));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void waiveAndRefund_whenSessionNotFound_unlocksLock() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-LOCK-2");
        when(distributedLockService.tryLock(
                SettlementService.sessionSettleLockKey("S-LOCK-2"), 60L, 5L))
                .thenReturn(true);
        when(sessionRepository.findByIdForUpdate("S-LOCK-2")).thenReturn(java.util.Optional.of(session));
        when(orderRepository.findBySessionId("S-LOCK-2")).thenReturn(java.util.Optional.empty());

        int refunded = settlementService.waiveAndRefund(session);

        assertEquals(0, refunded);
        verify(distributedLockService).unlock(SettlementService.sessionSettleLockKey("S-LOCK-2"));
    }
}
