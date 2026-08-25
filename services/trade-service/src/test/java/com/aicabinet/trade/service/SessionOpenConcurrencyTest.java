package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateSessionRequest;
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
class SessionOpenConcurrencyTest {

    @Mock private DistributedLockService distributedLockService;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, distributedLockService);
    }

    @Test
    void createSession_whenDeviceOpenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(SessionService.sessionOpenLockKey("CAB-001")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSession(1L, new CreateSessionRequest("CAB-001", null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
