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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionOpenConcurrencyTest {

    @Mock private DistributedLockService distributedLockService;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void createSession_whenDeviceOpenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SessionService.sessionOpenLockKey("CAB-001"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSession(1L, new CreateSessionRequest("CAB-001", null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
