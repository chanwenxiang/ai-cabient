package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserAccount;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumerPreauthConcurrencyTest {

    @Mock private UserAccountMapper accountRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private DistributedLockService distributedLockService;

    private ConsumerPreauthService service;

    @BeforeEach
    void setUp() {
        CheckoutProperties checkoutProperties = new CheckoutProperties(false, 500);
        service = new ConsumerPreauthService(
                accountRepository, sessionRepository, deviceRepository,
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
                eq(ConsumerPreauthService.preauthLockKey(10001L)), eq(60L), eq(5L)))
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
                eq(ConsumerPreauthService.preauthLockKey(10002L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(sessionRepository.findByIdForUpdate("S-2")).thenReturn(Optional.of(locked));
        when(accountRepository.findByIdForUpdate(10002L)).thenReturn(Optional.of(account));

        service.freezeForOpen(session, false);

        verify(accountRepository).findByIdForUpdate(10002L);
        verify(sessionRepository).findByIdForUpdate("S-2");
        verify(distributedLockService).unlock(ConsumerPreauthService.preauthLockKey(10002L));
    }
}
