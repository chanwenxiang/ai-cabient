package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.AnnouncementMapper;
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
class AnnouncementConcurrencyTest {

    @Mock private AnnouncementMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        service = new AnnouncementService(repository, distributedLockService);
    }

    @Test
    void publish_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(AnnouncementService.announcementLockKey(1L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.publish(1L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void archive_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(AnnouncementService.announcementLockKey(2L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(repository.findByIdForUpdate(2L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.archive(2L));

        verify(distributedLockService).unlock(AnnouncementService.announcementLockKey(2L));
    }
}
