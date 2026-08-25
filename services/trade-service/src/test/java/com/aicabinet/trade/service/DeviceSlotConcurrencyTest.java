package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.*;
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
class DeviceSlotConcurrencyTest {

    @Mock private DeviceSlotMapper slotRepository;
    @Mock private DeviceSkuLotMapper lotRepository;
    @Mock private DistributedLockService distributedLockService;

    private DeviceSlotService service;

    @BeforeEach
    void setUp() {
        service = new DeviceSlotService(
                slotRepository, lotRepository, null, null, null, null, null, null,
                null, null, null, null, distributedLockService);
    }

    @Test
    void applyPhysicalSnapshot_whenSlotLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceSlotService.deviceSlotLockKey("CAB-SLOT-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyPhysicalSnapshot("CAB-SLOT-1", java.util.Map.of("A1", 3),
                        "TEST", "REF-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
