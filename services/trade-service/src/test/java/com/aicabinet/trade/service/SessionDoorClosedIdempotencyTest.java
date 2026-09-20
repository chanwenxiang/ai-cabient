package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.OrderReadModel;
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
import static org.mockito.Mockito.never;
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

    private SessionService service;
    private SessionDoorService doorService;

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, cabinetMetrics, domainEventPublisher,
                gravityHelper, null, null, null, null, null, null, userInfoRepository, orderRepository,
                consumerPreauthService, distributedLockService, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        SessionSettleService settleService = new SessionSettleService(
                repository, settlementService, visionAsyncProperties, cabinetMetrics, opsExceptionService, service);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionSettleService", settleService);
        doorService = new SessionDoorService(repository, gravityHelper, restockSnapshotService, null,
                cabinetMetrics, domainEventPublisher, service, null);
        org.springframework.test.util.ReflectionTestUtils.setField(doorService, "self", doorService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionDoorService", doorService);
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
        when(settlementService.settle(session)).thenReturn(OrderReadModelFixtures.sample("O-Q3", "S-Q3"));

        DoorEventRequest first = new DoorEventRequest(
                "S-Q3", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://v1");
        DoorEventRequest second = new DoorEventRequest(
                "S-Q3", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://v2");

        assertEquals(SessionState.COMPLETED, service.handleDoorEvent(first).state());
        assertEquals(SessionState.COMPLETED, service.handleDoorEvent(second).state());
        assertEquals("s3://v2", session.getVideoUri());
        verify(settlementService, times(1)).settle(session);
        // 顺手断言完整率计数：两次 CLOSED 只应记**一次** normal（第二次已非 SHOPPING ⇒ 不重复计数）
        verify(cabinetMetrics, times(1)).recordDoorClose(false);
        verify(cabinetMetrics, never()).recordDoorClose(true);
    }

    /**
     * 关门完整率的分子来源：从 {@code SHOPPING} 正常关门记一次 {@code normal}；
     * 同一会话的第二个 CLOSED（会话已 COMPLETED、非 SHOPPING）**不再记** ——
     * 否则同一会话被重复计数，完整率会被推高。
     */
    @Test
    void doorClosed_normalPath_recordsNormalExactlyOnce() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-CLOSE-1");
        session.setDeviceId("CAB-001");
        session.setUserId(7L);
        session.setState(SessionState.SHOPPING);

        when(repository.findByIdForUpdate("S-CLOSE-1")).thenReturn(Optional.of(session));
        when(repository.findById("S-CLOSE-1")).thenReturn(Optional.of(session));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(session)).thenReturn(OrderReadModelFixtures.sample("O-C1", "S-CLOSE-1"));

        service.handleDoorEvent(new DoorEventRequest(
                "S-CLOSE-1", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://a"));
        service.handleDoorEvent(new DoorEventRequest(
                "S-CLOSE-1", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://b"));

        verify(cabinetMetrics, times(1)).recordDoorClose(false);
        verify(cabinetMetrics, never()).recordDoorClose(true);
    }

    /**
     * 孤儿关门：**没收到 OPEN 就收到 CLOSED**（开门报文丢失/重放）。
     *
     * <p>必须记 {@code orphan=true} —— 该会话没有对应的 {@code doorOpen{result=success}} 进分母，
     * 若把它当 normal 混进分子，「关门完整率」会算出 **>100%**（本指标刻意要防的假绿）。
     */
    @Test
    void doorClosed_withoutOpenEvent_recordsOrphanNotNormal() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-ORPHAN");
        session.setDeviceId("CAB-001");
        session.setUserId(7L);
        session.setState(SessionState.OPENING);

        when(repository.findByIdForUpdate("S-ORPHAN")).thenReturn(Optional.of(session));
        when(repository.findById("S-ORPHAN")).thenReturn(Optional.of(session));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(session)).thenReturn(OrderReadModelFixtures.sample("O-OR", "S-ORPHAN"));

        service.handleDoorEvent(new DoorEventRequest(
                "S-ORPHAN", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(), "s3://o"));

        verify(cabinetMetrics, times(1)).recordDoorClose(true);
        verify(cabinetMetrics, never()).recordDoorClose(false);
    }
}
