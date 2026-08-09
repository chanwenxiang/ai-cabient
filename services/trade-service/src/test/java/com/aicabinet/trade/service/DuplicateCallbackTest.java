package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FileDisputeRequest;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuplicateCallbackTest {

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
    @Mock DisputeTicketMapper disputeRepository;
    @Mock PermissionService permissionService;
    @Mock RiskControlService riskControlService;
    @Mock CabinetOrderMapper orderRepository;
    @Mock VideoArchiveService videoArchiveService;

    private SessionService sessionService;
    private DisputeService disputeService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, visionAsyncProperties, cabinetMetrics, domainEventPublisher,
                gravityHelper, restockSnapshotService, null, opsExceptionService, null, orderRepository, null, null);
        disputeService = new DisputeService(
                disputeRepository, null, repository, null, null, null, null, null,
                riskControlService, permissionService, null, null, null, null,
                new DisputeSlaProperties(24, 12, "", false), null, opsExceptionService, null, null,
                videoArchiveService);
    }

    @Test
    void duplicateDoorCloseSettlement_onlySettlesOnce() {
        ShoppingSession recognizing = session("S-DUP-01", SessionState.RECOGNIZING);
        ShoppingSession completed = session("S-DUP-01", SessionState.COMPLETED);
        completed.setOrderId("O-DUP-01");
        stubFindById("S-DUP-01", recognizing, completed);
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(recognizing)).thenReturn(sampleOrder("O-DUP-01", "S-DUP-01"));
        when(orderRepository.findById("O-DUP-01")).thenReturn(Optional.empty());

        sessionService.settleAfterClose("S-DUP-01");
        var second = sessionService.settleAfterClose("S-DUP-01");

        assertEquals(SessionState.COMPLETED, second.state());
        verify(settlementService, times(1)).settle(recognizing);
    }

    @Test
    void duplicateAsyncRecognitionCallback_onlyProcessesOnce() {
        ShoppingSession recognizing = session("S-DUP-02", SessionState.RECOGNIZING);
        ShoppingSession settling = session("S-DUP-02", SessionState.SETTLING);
        stubFindById("S-DUP-02", recognizing, settling);
        VisionServiceClient.RecognitionResult recognition = sampleRecognition();
        when(settlementService.processRecognitionResult(recognizing, recognition))
                .thenReturn(sampleOrder("O-DUP-02", "S-DUP-02"));

        sessionService.completeAsyncRecognition("S-DUP-02", recognition);
        sessionService.completeAsyncRecognition("S-DUP-02", recognition);

        verify(settlementService, times(1)).processRecognitionResult(recognizing, recognition);
    }

    @Test
    void duplicateConsumerAppeal_returnsConflict() {
        ShoppingSession shoppingSession = session("S-DUP-03", SessionState.COMPLETED);
        when(repository.findById("S-DUP-03")).thenReturn(Optional.of(shoppingSession));
        when(disputeRepository.findBySessionId("S-DUP-03")).thenReturn(Optional.of(new com.aicabinet.trade.domain.DisputeTicket()));

        assertConflict(
                assertThrows(ResponseStatusException.class,
                        () -> disputeService.fileByConsumer(7L, new FileDisputeRequest("S-DUP-03", "wrong-charge", "BILL", "NORMAL"))),
                ApiMessages.DISPUTE_ALREADY_EXISTS);
    }

    @Test
    void resolvedConsumerAppeal_returnsClosedMessage() {
        ShoppingSession shoppingSession = session("S-DUP-04", SessionState.COMPLETED);
        com.aicabinet.trade.domain.DisputeTicket ticket = new com.aicabinet.trade.domain.DisputeTicket();
        ticket.setStatus("RESOLVED");
        when(repository.findById("S-DUP-04")).thenReturn(Optional.of(shoppingSession));
        when(disputeRepository.findBySessionId("S-DUP-04")).thenReturn(Optional.of(ticket));

        assertConflict(
                assertThrows(ResponseStatusException.class,
                        () -> disputeService.fileByConsumer(7L, new FileDisputeRequest("S-DUP-04", "re-appeal", "BILL", "NORMAL"))),
                ApiMessages.DISPUTE_APPEAL_CLOSED);
    }

    private static void assertConflict(ResponseStatusException thrown, String expectedReason) {
        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertEquals(expectedReason, thrown.getReason());
    }

    private void stubFindById(String sessionId, ShoppingSession first, ShoppingSession second) {
        AtomicInteger findCalls = new AtomicInteger();
        Answer<Optional<ShoppingSession>> answer = invocation -> {
            if (findCalls.getAndIncrement() == 0) {
                return Optional.of(first);
            }
            return Optional.of(second);
        };
        when(repository.findById(sessionId)).thenAnswer(answer);
    }

    private static VisionServiceClient.RecognitionResult sampleRecognition() {
        return new VisionServiceClient.RecognitionResult(
                "T-1", List.of(), 0.9f, false, "mock", List.of());
    }

    private static OrderDto sampleOrder(String orderId, String sessionId) {
        return new OrderDto(
                orderId, sessionId, 7L, "CAB-001", 500,
                List.of(), "PAID", "BALANCE", null, 1000, 500, null);
    }

    private ShoppingSession session(String id, SessionState state) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(id);
        session.setUserId(7L);
        session.setDeviceId("CAB-001");
        session.setState(state);
        return session;
    }
}
