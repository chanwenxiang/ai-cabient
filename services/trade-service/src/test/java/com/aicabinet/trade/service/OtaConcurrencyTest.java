package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OtaReleaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtaConcurrencyTest {

    @Mock private OtaReleaseMapper releaseRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private OtaCdnService otaCdnService;
    @Mock private DistributedLockService distributedLockService;

    private OtaService service;

    @BeforeEach
    void setUp() {
        service = new OtaService(
                releaseRepository,
                deviceRepository,
                otaCdnService,
                new ObjectMapper(),
                distributedLockService);
    }

    @Test
    void unpublishRelease_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                OtaService.otaReleaseLockKey(7L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.unpublishRelease(1L, 7L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void reportVersion_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                OtaService.otaDeviceVersionLockKey("DEV-001"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reportVersion("DEV-001", "1.2.3"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void unpublishRelease_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                OtaService.otaReleaseLockKey(8L), 60L, 5L))
                .thenReturn(true);
        when(releaseRepository.findByIdForUpdate(8L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.unpublishRelease(1L, 8L));

        verify(distributedLockService).unlock(OtaService.otaReleaseLockKey(8L));
    }
}
