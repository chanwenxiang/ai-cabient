package com.aicabinet.trade.service;

import com.aicabinet.common.dto.VisionAnomalyEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionAnomalyIngestConcurrencyTest {

    @Mock private OpsExceptionService opsExceptionService;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private DistributedLockService distributedLockService;

    private VisionAnomalyIngestService service;

    @BeforeEach
    void setUp() {
        service = new VisionAnomalyIngestService(opsExceptionService, opsAlertDispatcher, distributedLockService);
    }

    @Test
    void ingest_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(VisionAnomalyIngestService.visionAnomalyDeviceLockKey("CAB-900")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ingest(List.of(
                        new VisionAnomalyEventDto("CAB-900", "S1", "TAMPER", 0.9,
                                null, "QUECTEL", Instant.now()))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void ingest_whenRecordMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(VisionAnomalyIngestService.visionAnomalyDeviceLockKey("CAB-901")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(opsExceptionService.report(
                eq("VISION_ANOMALY"), eq("MEDIUM"),
                eq(new OpsExceptionService.ExceptionReport.ExceptionRefs("CAB-901", "S2", null, null)),
                eq("商品错拿"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);

        service.ingest(List.of(
                new VisionAnomalyEventDto("CAB-901", "S2", "ITEM_MISPLACE", 0.5,
                        null, "QUECTEL", Instant.now())));

        verify(distributedLockService).unlock(VisionAnomalyIngestService.visionAnomalyDeviceLockKey("CAB-901"));
    }
}
