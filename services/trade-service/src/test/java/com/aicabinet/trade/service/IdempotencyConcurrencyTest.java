package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.IdempotencyKeyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyConcurrencyTest {

    @Mock private IdempotencyKeyMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "distributedLockService", distributedLockService);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void saveIdempotency_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(IdempotencyService.idempotencyLockKey("key-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveIdempotency("key-1", "TEST", "biz-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deleteIdempotency_whenLockAcquired_unlocksAfterDelete() {
        when(distributedLockService.tryLock(
                eq(IdempotencyService.idempotencyLockKey("key-2")), eq(60L), eq(5L)))
                .thenReturn(true);

        service.deleteIdempotency("key-2");

        verify(repository).deleteById("key-2");
        verify(distributedLockService).unlock(IdempotencyService.idempotencyLockKey("key-2"));
    }
}
