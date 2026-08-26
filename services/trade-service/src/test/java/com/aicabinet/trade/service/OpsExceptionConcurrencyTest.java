package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.OpsExceptionMapper;
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
class OpsExceptionConcurrencyTest {

    @Mock private OpsExceptionMapper repository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;

    private OpsExceptionService service;

    @BeforeEach
    void setUp() {
        service = new OpsExceptionService(repository, permissionService, null, null,
                null, null, null, null, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void claim_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(OpsExceptionService.exceptionLockKey("EX-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.claim(10001L, "EX-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void resolve_whenExceptionNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(OpsExceptionService.exceptionLockKey("EX-2")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(repository.findByIdForUpdate("EX-2")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.resolve(10001L, "EX-2", "done"));

        verify(distributedLockService).unlock(OpsExceptionService.exceptionLockKey("EX-2"));
    }

    @Test
    void report_whenDedupLockBusy_rejectsWithConflict() {
        String dedup = "OPEN_TIMEOUT:S-1";
        when(distributedLockService.tryLock(
                eq(OpsExceptionService.exceptionDedupLockKey(dedup)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.report("OPEN_TIMEOUT", "HIGH", "CAB-1", "S-1",
                        null, 1L, "title", "detail"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
