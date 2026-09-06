package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceRecoveryTest {

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
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    @Test
    void idempotentReplay_returnsExistingSessionWithoutOpeningAgain() {
        ShoppingSession existing = session("S1", 7L, "CAB-001", SessionState.OPENING);
        existing.setIdempotencyKey("open-1");
        when(repository.findByIdempotencyKey("open-1")).thenReturn(Optional.of(existing));

        var result = service.createSession(7L, new CreateSessionRequest("CAB-001", " open-1 "));

        assertEquals("S1", result.sessionId());
        verify(deviceClient, never()).requestOpenDoor(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void idempotentReplay_rejectsDifferentOwnerOrDevice() {
        ShoppingSession existing = session("S1", 8L, "CAB-002", SessionState.OPENING);
        when(repository.findByIdempotencyKey("open-1")).thenReturn(Optional.of(existing));

        CreateSessionRequest request = new CreateSessionRequest("CAB-001", "open-1");
        assertThrows(ResponseStatusException.class, () -> service.createSession(7L, request));
    }

    @Test
    void activeSession_returnsNullWhenUserHasNoOpenFlow() {
        when(repository.findFirstByUserIdAndStateInOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(7L), anyCollection())).thenReturn(Optional.empty());

        assertNull(service.getActiveSession(7L));
    }

    @Test
    void cancelOpening_isIdempotent() {
        ShoppingSession existing = session("S1", 7L, "CAB-001", SessionState.CANCELLED);
        when(repository.findByIdForUpdate("S1")).thenReturn(Optional.of(existing));

        assertEquals(SessionState.CANCELLED, service.cancelSession(7L, "S1").state());
        verify(repository, never()).save(existing);
    }

    @Test
    void recognitionDispute_createsHighPriorityOpsException() {
        ShoppingSession existing = session("S-DISPUTED", 7L, "CAB-001", SessionState.RECOGNIZING);
        when(repository.findByIdForUpdate("S-DISPUTED")).thenReturn(Optional.of(existing));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(existing))
                .thenThrow(new DisputeRequiredException("识别服务暂时不可用，已转人工审核，本次暂未扣款"));

        var result = service.settleAfterClose("S-DISPUTED");

        assertEquals(SessionState.DISPUTED, result.state());
        verify(opsExceptionService).report("RECOGNITION_FAILED", "HIGH",
                new OpsExceptionService.ExceptionReport.ExceptionRefs("CAB-001", "S-DISPUTED", null, 7L),
                "识别结果需人工审核", "识别服务暂时不可用，已转人工审核，本次暂未扣款");
    }

    @Test
    void expireStaleConsumerShopping_cancelsAndResolvesDoorOpenAlert() {
        ShoppingSession stale = session("S-OPEN", 7L, "CAB-001", SessionState.SHOPPING);
        stale.setOpenTime(java.time.Instant.now().minus(java.time.Duration.ofMinutes(11)));
        when(taskService.tryBegin("session-door-open-expire", 600)).thenReturn(true);
        when(repository.findByStateAndOpenTimeBefore(
                org.mockito.ArgumentMatchers.eq(SessionState.SHOPPING),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(500)))
                .thenReturn(java.util.List.of(stale));
        when(repository.findByIdForUpdate("S-OPEN")).thenReturn(Optional.of(stale));

        service.expireStaleConsumerShoppingSessions();

        assertEquals(SessionState.CANCELLED, stale.getState());
        assertEquals("开门超时自动关闭（超过10分钟未关门）", stale.getFailReason());
        verify(consumerPreauthService).releaseIfFrozen(stale);
        verify(repository).save(stale);
        verify(opsExceptionService).report(
                org.mockito.ArgumentMatchers.eq("DOOR_OPEN_TOO_LONG"),
                org.mockito.ArgumentMatchers.eq("CRITICAL"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("柜门长时间未关闭"),
                org.mockito.ArgumentMatchers.contains("已自动关闭会话"));
        verify(opsExceptionService).resolveSystem("DOOR_OPEN_TOO_LONG", "S-OPEN",
                "开门超时已自动关闭会话并释放设备");
        verify(taskService).finish(
                org.mockito.ArgumentMatchers.eq("session-door-open-expire"),
                org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.contains("关闭开门超时购物会话 1"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private ShoppingSession session(String id, Long userId, String deviceId, SessionState state) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(id);
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setState(state);
        return session;
    }
}
