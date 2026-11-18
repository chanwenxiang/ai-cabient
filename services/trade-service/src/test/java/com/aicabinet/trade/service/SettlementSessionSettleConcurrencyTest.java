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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementSessionSettleConcurrencyTest {

    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;

    private SettlementService settlementService;
    private SettlementWaiveRefundService waiveRefundService;

    @BeforeEach
    void setUp() {
        waiveRefundService = new SettlementWaiveRefundService(
                sessionRepository, orderRepository, null,
                null, null, null, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(waiveRefundService, "self", waiveRefundService);
        settlementService = new SettlementService(
                sessionRepository, orderRepository,
                waiveRefundService, null, null, null, null,
                null, distributedLockService);
        SettlementSettleOrchestrator settleOrchestrator = new SettlementSettleOrchestrator(
                sessionRepository, orderRepository, null, null, settlementService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(settleOrchestrator, "self", settleOrchestrator);
        org.springframework.test.util.ReflectionTestUtils.setField(
                settlementService, "settleOrchestrator", settleOrchestrator);
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
        when(sessionRepository.findById("S-LOCK-2")).thenReturn(java.util.Optional.of(session));
        when(orderRepository.findBySessionId("S-LOCK-2")).thenReturn(java.util.Optional.empty());

        int refunded = settlementService.waiveAndRefund(session);

        assertEquals(0, refunded);
        verify(distributedLockService).unlock(SettlementService.sessionSettleLockKey("S-LOCK-2"));
    }

    /** F3b：60s 租约必须边跑边续；心跳停必须早于 unlock，否则停心跳前锁已易主仍去解锁别人的锁。 */
    @Test
    void sessionSettleLock_heartbeatStopsBeforeUnlock() {
        String key = SettlementService.sessionSettleLockKey("S-HB-1");
        LockHeartbeat heartbeat = mock(LockHeartbeat.class);
        java.util.concurrent.ScheduledFuture<?> future = mock(java.util.concurrent.ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(heartbeat).start(eq(key), anyLong());
        org.springframework.test.util.ReflectionTestUtils.setField(
                settlementService, "settleLockHeartbeat", heartbeat);
        when(distributedLockService.tryLock(key, 60L, 5L)).thenReturn(true);

        assertEquals("done", settlementService.runWithSessionSettleLock("S-HB-1", () -> "done"));

        verify(heartbeat).start(eq(key), eq(Thread.currentThread().getId()));
        org.mockito.InOrder inOrder = inOrder(heartbeat, distributedLockService);
        inOrder.verify(heartbeat).stop(same(future));
        inOrder.verify(distributedLockService).unlock(key);
    }

    /** 同一把锁的第二份实现（豁免退款）也必须续租——只改一处等于没改。 */
    @Test
    void waiveRefundLock_heartbeatStopsBeforeUnlock() {
        String key = SettlementService.sessionSettleLockKey("S-HB-2");
        LockHeartbeat heartbeat = mock(LockHeartbeat.class);
        java.util.concurrent.ScheduledFuture<?> future = mock(java.util.concurrent.ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(heartbeat).start(eq(key), anyLong());
        org.springframework.test.util.ReflectionTestUtils.setField(
                waiveRefundService, "settleLockHeartbeat", heartbeat);
        when(distributedLockService.tryLock(key, 60L, 5L)).thenReturn(true);

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-HB-2");
        assertEquals(0, settlementService.waiveAndRefund(session));

        verify(heartbeat).start(eq(key), eq(Thread.currentThread().getId()));
        org.mockito.InOrder inOrder = inOrder(heartbeat, distributedLockService);
        inOrder.verify(heartbeat).stop(same(future));
        inOrder.verify(distributedLockService).unlock(key);
    }

    /** 拿锁失败时不得启动心跳：没有锁却去续租，会把别人的锁 TTL 顶长。 */
    @Test
    void sessionSettleLock_whenLockBusy_doesNotStartHeartbeat() {
        String key = SettlementService.sessionSettleLockKey("S-HB-3");
        LockHeartbeat heartbeat = mock(LockHeartbeat.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                settlementService, "settleLockHeartbeat", heartbeat);
        when(distributedLockService.tryLock(key, 60L, 5L)).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> settlementService.runWithSessionSettleLock("S-HB-3", () -> "never"));

        verify(heartbeat, org.mockito.Mockito.never()).start(eq(key), anyLong());
    }
}
