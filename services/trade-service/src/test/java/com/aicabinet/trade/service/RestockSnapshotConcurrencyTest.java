package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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
class RestockSnapshotConcurrencyTest {

    @Mock private GravitySettlementHelper gravityHelper;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private DistributedLockService distributedLockService;

    private RestockSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new RestockSnapshotService(gravityHelper, deviceSlotService, distributedLockService,
                org.mockito.Mockito.mock(ShoppingSessionMapper.class));
    }

    @Test
    void applySnapshot_whenLockBusy_rejectsWithConflict() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-LOCK-1");
        session.setDeviceId("CAB-001");
        when(distributedLockService.tryLock(
                SessionService.sessionLifeLockKey("S-LOCK-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applySnapshot(session));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
