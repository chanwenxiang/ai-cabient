package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.UserNotifyPrefMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumerNotifyPrefConcurrencyTest {

    @Mock private UserNotifyPrefMapper prefRepository;
    @Mock private DistributedLockService distributedLockService;

    private ConsumerNotifyPrefService service;

    @BeforeEach
    void setUp() {
        service = new ConsumerNotifyPrefService(prefRepository, distributedLockService);
    }

    @Test
    void update_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(ConsumerNotifyPrefService.notifyPrefLockKey(42L, "ORDER")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.update(42L, "ORDER", false));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void update_whenLockAcquired_unlocksAfterSave() {
        when(distributedLockService.tryLock(
                eq(ConsumerNotifyPrefService.notifyPrefLockKey(42L, "ORDER")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(prefRepository.findByUserIdAndCategoryForUpdate(42L, "ORDER"))
                .thenReturn(java.util.Optional.empty());

        service.update(42L, "ORDER", false);

        verify(distributedLockService).unlock(ConsumerNotifyPrefService.notifyPrefLockKey(42L, "ORDER"));
        verify(prefRepository).insert(org.mockito.ArgumentMatchers.any());
    }
}
