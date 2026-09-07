package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionDoorClosedIdempotencyTest {

    @Mock ShoppingSessionMapper repository;
    @Mock DeviceServiceClient deviceClient;
    @Mock UserValidationService userValidationService;
    @Mock DeviceValidationService deviceValidationService;
    @Mock SettlementService settlementService;
    @Mock VisionAsyncProperties visionAsyncProperties;
    @Mock CabinetMetrics cabinetMetrics;
    @Mock DomainEventPublisher domainEventPublisher;
    @Mock GravitySettlementHelper gravityHelper;
    @Mock RestockSnapshotService restockSnapshotService;
    @Mock OpsExceptionService opsExceptionService;
    @Mock com.aicabinet.trade.mapper.UserInfoMapper userInfoRepository;
    @Mock com.aicabinet.trade.mapper.CabinetOrderMapper orderRepository;
    @Mock ConsumerPreauthService consumerPreauthService;
    @Mock DistributedLockService distributedLockService;
    @Mock ScheduledTaskService taskService;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, visionAsyncProperties, cabinetMetrics, domainEventPublisher,
                gravityHelper, restockSnapshotService, null, opsExceptionService, userInfoRepository, orderRepository,
                null, null, consumerPreauthService, null, distributedLockService, taskService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Q3 trade 侧：不同 eventSeq 的二次 CLOSED 可再进 trade，但非 RECOGNIZING 时不二次 settle。
     */
    @Test
    void secondClosed_afterCompleted_doesNotSettleAgain() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-Q3");
        session.setDeviceId("CAB-001");
        session.setUserId(7L);
        session.setState(SessionState.SHOPPING);

        when(repository.findByIdForUpdate("S-Q3")).thenReturn(Optional.of(session));
        when(repository.findById("S-Q3")).thenReturn(Optional.of(session));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(session)).thenReturn(new OrderDto(
                "O-Q3", "S-Q3", 7L, "CAB-001", 100, List.of(), "PAID",
                "BALANCE", null, null, null, Instant.now()));

        DoorEventRequest first = new DoorEventRequest(
                "S-Q3", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://v1");
        DoorEventRequest second = new DoorEventRequest(
                "S-Q3", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://v2");

        assertEquals(SessionState.COMPLETED, service.handleDoorEvent(first).state());
        assertEquals(SessionState.COMPLETED, service.handleDoorEvent(second).state());
        assertEquals("s3://v2", session.getVideoUri());
        verify(settlementService, times(1)).settle(session);
    }
}
