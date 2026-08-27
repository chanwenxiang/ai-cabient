package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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
class SessionLifeConcurrencyTest {

    @Mock private ShoppingSessionMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(
                repository, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void settleAfterClose_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(SessionService.sessionLifeLockKey("S-LOCK-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.settleAfterClose("S-LOCK-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void cancelSession_whenSessionNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(SessionService.sessionLifeLockKey("S-LOCK-2")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(repository.findByIdForUpdate("S-LOCK-2")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.cancelSession(1L, "S-LOCK-2"));

        verify(distributedLockService).unlock(SessionService.sessionLifeLockKey("S-LOCK-2"));
    }

    @Test
    void settleAfterClose_whenNotRecognizing_unlocksWithoutSettling() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-LOCK-3");
        session.setState(SessionState.COMPLETED);
        when(distributedLockService.tryLock(
                eq(SessionService.sessionLifeLockKey("S-LOCK-3")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(repository.findByIdForUpdate("S-LOCK-3")).thenReturn(Optional.of(session));

        service.settleAfterClose("S-LOCK-3");

        verify(distributedLockService).unlock(SessionService.sessionLifeLockKey("S-LOCK-3"));
    }
}
