package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
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

    @Mock ShoppingSessionRepository repository;
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

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, visionAsyncProperties, cabinetMetrics, domainEventPublisher,
                gravityHelper, restockSnapshotService, null, opsExceptionService);
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

        assertThrows(ResponseStatusException.class,
                () -> service.createSession(7L, new CreateSessionRequest("CAB-001", "open-1")));
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
        when(repository.findById("S1")).thenReturn(Optional.of(existing));

        assertEquals(SessionState.CANCELLED, service.cancelSession(7L, "S1").state());
        verify(repository, never()).save(existing);
    }

    @Test
    void recognitionDispute_createsHighPriorityOpsException() {
        ShoppingSession existing = session("S-DISPUTED", 7L, "CAB-001", SessionState.RECOGNIZING);
        when(repository.findById("S-DISPUTED")).thenReturn(Optional.of(existing));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(existing))
                .thenThrow(new DisputeRequiredException("识别服务暂时不可用，已转人工审核，本次暂未扣款"));

        var result = service.settleAfterClose("S-DISPUTED");

        assertEquals(SessionState.DISPUTED, result.state());
        verify(opsExceptionService).report("RECOGNITION_FAILED", "HIGH", "CAB-001",
                "S-DISPUTED", null, 7L, "识别结果需人工审核",
                "识别服务暂时不可用，已转人工审核，本次暂未扣款");
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
