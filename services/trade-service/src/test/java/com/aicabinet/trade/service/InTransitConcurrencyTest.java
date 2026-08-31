package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InTransitConcurrencyTest {

    @Mock private WarehouseInTransitMapper transitRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;

    private InTransitService service;

    @BeforeEach
    void setUp() {
        service = new InTransitService(transitRepository, distributedLockService, displaySnapshotHelper, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void receiveForDevice_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InTransitService.inTransitLockKey(10L, "CAB-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.receiveForDevice(10L, "CAB-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void cancelOpenForDevice_whenNoRows_unlocksLock() {
        when(distributedLockService.tryLock(
                InTransitService.inTransitLockKey(11L, "CAB-2"), 60L, 5L))
                .thenReturn(true);
        when(transitRepository.findByOutboundIdAndDeviceIdAndStatusForUpdate(11L, "CAB-2", "IN_TRANSIT"))
                .thenReturn(List.of());

        service.cancelOpenForDevice(11L, "CAB-2");

        verify(distributedLockService).unlock(InTransitService.inTransitLockKey(11L, "CAB-2"));
    }
}
