package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.ConsumerPreauthHold;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.ConsumerPreauthHoldMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumerPreauthConcurrencyTest {

    @Mock private UserAccountMapper accountRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private ConsumerPreauthHoldMapper holdRepository;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private DistributedLockService distributedLockService;

    private ConsumerPreauthService service;

    @BeforeEach
    void setUp() {
        CheckoutProperties checkoutProperties = new CheckoutProperties(false, 500);
        service = new ConsumerPreauthService(
                accountRepository, sessionRepository, deviceRepository, holdRepository,
                checkoutProperties, balanceLedgerService, systemConfigService,
                distributedLockService);
    }

    @Test
    void freezeForOpen_whenLockBusy_rejectsWithConflict() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-1");
        session.setUserId(10001L);
        session.setDeviceId("CAB-1");

        when(distributedLockService.tryLock(
                ConsumerPreauthService.preauthLockKey(10001L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.freezeForOpen(session, false));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void freezeForOpen_acquiresLockAndFreezes() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-2");
        session.setUserId(10002L);
        session.setDeviceId("CAB-2");
        session.setPreauthStatus(ConsumerPreauthService.STATUS_NONE);

        ShoppingSession locked = new ShoppingSession();
        locked.setSessionId("S-2");
        locked.setUserId(10002L);
        locked.setDeviceId("CAB-2");
        locked.setPreauthStatus(ConsumerPreauthService.STATUS_NONE);

        UserAccount account = new UserAccount();
        account.setUserId(10002L);
        account.setBalanceCents(10_000);
        account.setFrozenCents(0);

        when(distributedLockService.tryLock(
                ConsumerPreauthService.preauthLockKey(10002L), 60L, 5L))
                .thenReturn(true);
        when(sessionRepository.findByIdForUpdate("S-2")).thenReturn(Optional.of(locked));
        when(holdRepository.findByIdForUpdate("S-2")).thenReturn(Optional.empty());
        when(holdRepository.findById("S-2")).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(10002L)).thenReturn(Optional.of(account));

        service.freezeForOpen(session, false);

        verify(accountRepository).findByIdForUpdate(10002L);
        verify(sessionRepository).findByIdForUpdate("S-2");
        verify(distributedLockService).unlock(ConsumerPreauthService.preauthLockKey(10002L));
    }

    /**
     * M1: 同用户双会话先后冻结；释放 A 后 B 仍 FROZEN，账户 frozen = B.hold。
     */
    @Test
    void releaseSessionA_keepsSessionBFrozen_andAccountFrozenEqualsBHold() {
        long userId = 20001L;
        int holdCents = 500;

        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setBalanceCents(20_000);
        account.setFrozenCents(0);

        ShoppingSession sessionA = openSession("S-A", userId, "CAB-A");
        ShoppingSession sessionB = openSession("S-B", userId, "CAB-B");
        ShoppingSession lockedA = openSession("S-A", userId, "CAB-A");
        ShoppingSession lockedB = openSession("S-B", userId, "CAB-B");

        java.util.Map<String, ConsumerPreauthHold> holds = new java.util.HashMap<>();

        when(distributedLockService.tryLock(
                ConsumerPreauthService.preauthLockKey(userId), 60L, 5L))
                .thenReturn(true);
        when(deviceRepository.findById(anyString())).thenReturn(Optional.empty());
        when(systemConfigService.getInt(SystemConfigService.CHECKOUT_PREAUTH_CENTS, -1)).thenReturn(-1);
        when(accountRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(sessionRepository.findByIdForUpdate("S-A")).thenReturn(Optional.of(lockedA));
        when(sessionRepository.findByIdForUpdate("S-B")).thenReturn(Optional.of(lockedB));
        when(holdRepository.findByIdForUpdate(anyString())).thenAnswer(inv ->
                Optional.ofNullable(holds.get(inv.getArgument(0))));
        when(holdRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(holds.get(inv.getArgument(0))));
        when(holdRepository.save(any())).thenAnswer(inv -> {
            ConsumerPreauthHold h = inv.getArgument(0);
            holds.put(h.getSessionId(), h);
            return 1;
        });

        service.freezeForOpen(sessionA, false);
        service.freezeForOpen(sessionB, false);

        assertEquals(holdCents * 2, account.getFrozenCents());
        assertEquals(ConsumerPreauthService.STATUS_FROZEN, lockedA.getPreauthStatus());
        assertEquals(ConsumerPreauthService.STATUS_FROZEN, lockedB.getPreauthStatus());
        assertEquals(holdCents, holds.get("S-A").getHoldCents());
        assertEquals(holdCents, holds.get("S-B").getHoldCents());

        service.releaseIfFrozen(sessionA);

        assertEquals(ConsumerPreauthService.STATUS_RELEASED, lockedA.getPreauthStatus());
        assertEquals(ConsumerPreauthService.STATUS_RELEASED, holds.get("S-A").getStatus());
        assertEquals(ConsumerPreauthService.STATUS_FROZEN, lockedB.getPreauthStatus());
        assertEquals(ConsumerPreauthService.STATUS_FROZEN, holds.get("S-B").getStatus());
        assertEquals(holdCents, account.getFrozenCents());
        assertEquals(holdCents, holds.get("S-B").getHoldCents());
    }

    private static ShoppingSession openSession(String sessionId, long userId, String deviceId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setPreauthStatus(ConsumerPreauthService.STATUS_NONE);
        session.setPreauthCents(0);
        return session;
    }
}
