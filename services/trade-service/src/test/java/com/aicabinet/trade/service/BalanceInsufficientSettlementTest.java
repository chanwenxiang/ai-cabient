package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceInsufficientSettlementTest {

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
    @Mock CabinetOrderMapper orderRepository;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, visionAsyncProperties, cabinetMetrics, domainEventPublisher,
                gravityHelper, restockSnapshotService, null, opsExceptionService, null, orderRepository,
                null, null, null, null);
    }

    @Test
    void syncSettlement_balanceInsufficient_transitionsToDisputedWithoutOrder() {
        ShoppingSession session = session("S-BAL-01", 13800138000L, "CAB-001", SessionState.RECOGNIZING);
        when(repository.findById("S-BAL-01")).thenReturn(Optional.of(session));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(session)).thenThrow(
                new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE));

        var result = service.settleAfterClose("S-BAL-01");

        assertEquals(SessionState.DISPUTED, result.state());
        assertEquals(ApiMessages.INSUFFICIENT_BALANCE, session.getFailReason());
        assertNull(session.getOrderId());
        verify(opsExceptionService).report("BALANCE_INSUFFICIENT", "HIGH", "CAB-001",
                "S-BAL-01", null, 13800138000L, "结算余额不足", ApiMessages.INSUFFICIENT_BALANCE);
    }

    @Test
    void asyncRecognition_balanceInsufficient_transitionsToDisputedWithoutOrder() {
        ShoppingSession session = session("S-BAL-02", 13800138000L, "CAB-001", SessionState.RECOGNIZING);
        when(repository.findById("S-BAL-02")).thenReturn(Optional.of(session));
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(), 0.9f, false, "mock", List.of());
        when(settlementService.processRecognitionResult(session, recognition)).thenThrow(
                new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE));

        service.completeAsyncRecognition("S-BAL-02", recognition);

        assertEquals(SessionState.DISPUTED, session.getState());
        assertEquals(ApiMessages.INSUFFICIENT_BALANCE, session.getFailReason());
        assertNull(session.getOrderId());
        verify(opsExceptionService).report("BALANCE_INSUFFICIENT", "HIGH", "CAB-001",
                "S-BAL-02", null, 13800138000L, "结算余额不足", ApiMessages.INSUFFICIENT_BALANCE);
    }

    @Test
    void syncSettlement_exactBalance_completesNormally() {
        ShoppingSession session = session("S-BAL-03", 13800138000L, "CAB-001", SessionState.RECOGNIZING);
        when(repository.findById("S-BAL-03")).thenReturn(Optional.of(session));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(session)).thenReturn(new OrderDto(
                "O-EXACT", "S-BAL-03", 13800138000L, "CAB-001", 600,
                List.of(), "PAID", "BALANCE", null, 1100, 500, null));
        when(orderRepository.findById("O-EXACT")).thenReturn(Optional.empty());

        var result = service.settleAfterClose("S-BAL-03");

        assertEquals(SessionState.COMPLETED, result.state());
        assertEquals("O-EXACT", session.getOrderId());
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
