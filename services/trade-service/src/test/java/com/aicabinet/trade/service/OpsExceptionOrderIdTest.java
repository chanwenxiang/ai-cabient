package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsExceptionOrderIdTest {

    @Mock OpsExceptionMapper repository;
    @Mock PermissionService permissionService;
    @Mock AdminAuditService auditService;
    @Mock AdminAuditLogMapper auditRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock SettlementService settlementService;
    @Mock DisputeService disputeService;
    @Mock RepairTicketService repairTicketService;

    private OpsExceptionService service;

    @BeforeEach
    void setUp() {
        service = new OpsExceptionService(repository, permissionService, auditService, auditRepository,
                sessionRepository, settlementService, disputeService, repairTicketService);
    }

    @Test
    void manualResolveConfirm_writesOrderIdToExceptionAndSession() {
        OpsException item = openRecognitionException("EX-OID-001", "S-OID-001");
        ShoppingSession session = disputedSession("S-OID-001");
        OrderDto order = sampleOrder("ORD-CONFIRM-1", "S-OID-001");
        stubManualResolve(item, session);
        when(settlementService.confirmDisputedItems(eq(session), anyList()))
                .thenReturn(new SettlementService.ConfirmDisputeResult(order, 0, 500, 500));

        OpsExceptionDto dto = service.manualResolve(10001L, "EX-OID-001", "CONFIRM",
                List.of(new ResolveDisputeRequest.ManualLineItem("SKU-1", 1)),
                "ops-confirm-EX-OID-001", "orderId writeback");

        ArgumentCaptor<OpsException> saved = ArgumentCaptor.forClass(OpsException.class);
        verify(repository).save(saved.capture());
        assertEquals("ORD-CONFIRM-1", saved.getValue().getOrderId());
        assertEquals("ORD-CONFIRM-1", session.getOrderId());
        assertEquals("ORD-CONFIRM-1", dto.orderId());
        verify(sessionRepository).save(session);
        verify(disputeService).closeOpenTicketForSession(eq(10001L), eq("S-OID-001"), eq("CONFIRM"), any());
    }

    @Test
    void manualResolveAdjust_writesOrderIdToExceptionAndSession() {
        OpsException item = openRecognitionException("EX-OID-002", "S-OID-002");
        ShoppingSession session = disputedSession("S-OID-002");
        OrderDto order = sampleOrder("ORD-ADJUST-1", "S-OID-002");
        stubManualResolve(item, session);
        when(settlementService.confirmDisputedItems(eq(session), anyList()))
                .thenReturn(new SettlementService.ConfirmDisputeResult(order, 300, 500, 200));

        OpsExceptionDto dto = service.manualResolve(10001L, "EX-OID-002", "ADJUST",
                List.of(new ResolveDisputeRequest.ManualLineItem("SKU-1", 2)),
                "ops-adjust-EX-OID-002", "adjust orderId");

        assertEquals("ORD-ADJUST-1", item.getOrderId());
        assertEquals("ORD-ADJUST-1", session.getOrderId());
        assertEquals("ORD-ADJUST-1", dto.orderId());
    }

    @Test
    void manualResolveWaive_copiesSessionOrderIdWhenExceptionBlank() {
        OpsException item = openRecognitionException("EX-OID-003", "S-OID-003");
        ShoppingSession session = disputedSession("S-OID-003");
        session.setOrderId("ORD-EXISTING-1");
        stubManualResolve(item, session);
        when(settlementService.waiveAndRefund(session)).thenReturn(200);

        OpsExceptionDto dto = service.manualResolve(10001L, "EX-OID-003", "WAIVE", List.of(),
                "ops-waive-EX-OID-003", "fallback copy");

        assertEquals("ORD-EXISTING-1", item.getOrderId());
        assertEquals("ORD-EXISTING-1", dto.orderId());
    }

    @Test
    void detail_backfillsOrderIdFromSessionWhenExceptionBlank() {
        OpsException item = openRecognitionException("EX-OID-004", "S-OID-004");
        item.setStatus("RESOLVED");
        item.setOrderId(null);
        ShoppingSession session = disputedSession("S-OID-004");
        session.setOrderId("ORD-SESSION-BF");
        when(repository.findById("EX-OID-004")).thenReturn(Optional.of(item));
        when(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", "EX-OID-004"))
                .thenReturn(List.of());
        when(sessionRepository.findById("S-OID-004")).thenReturn(Optional.of(session));

        OpsExceptionDto dto = service.detail(10001L, "EX-OID-004").exception();

        assertNull(item.getOrderId());
        assertEquals("ORD-SESSION-BF", dto.orderId());
    }

    @Test
    void detail_keepsBlankOrderIdWhenSessionAlsoMissing() {
        OpsException item = openRecognitionException("EX-OID-005", "S-OID-005");
        item.setStatus("RESOLVED");
        item.setOrderId("  ");
        ShoppingSession session = disputedSession("S-OID-005");
        session.setOrderId(null);
        when(repository.findById("EX-OID-005")).thenReturn(Optional.of(item));
        when(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", "EX-OID-005"))
                .thenReturn(List.of());
        when(sessionRepository.findById("S-OID-005")).thenReturn(Optional.of(session));

        OpsExceptionDto dto = service.detail(10001L, "EX-OID-005").exception();

        assertNull(dto.orderId());
    }

    @Test
    void detail_backfillsUserIdFromSessionWhenExceptionBlank() {
        OpsException item = openRecognitionException("EX-OID-006", "S-OID-006");
        item.setStatus("OPEN");
        item.setUserId(null);
        item.setOrderId(null);
        ShoppingSession session = disputedSession("S-OID-006");
        session.setUserId(10001L);
        session.setOrderId("ORD-USER-BF");
        when(repository.findById("EX-OID-006")).thenReturn(Optional.of(item));
        when(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", "EX-OID-006"))
                .thenReturn(List.of());
        when(sessionRepository.findById("S-OID-006")).thenReturn(Optional.of(session));

        OpsExceptionDto dto = service.detail(10001L, "EX-OID-006").exception();

        assertNull(item.getUserId());
        assertEquals(10001L, dto.userId());
        assertEquals("ORD-USER-BF", dto.orderId());
    }

    private void stubManualResolve(OpsException item, ShoppingSession session) {
        when(repository.findById(item.getExceptionId())).thenReturn(Optional.of(item));
        when(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", item.getExceptionId()))
                .thenReturn(List.of());
        when(sessionRepository.findById(session.getSessionId())).thenReturn(Optional.of(session));
        when(repository.save(any(OpsException.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static OpsException openRecognitionException(String exceptionId, String sessionId) {
        OpsException item = new OpsException();
        item.setExceptionId(exceptionId);
        item.setExceptionType("RECOGNITION_FAILED");
        item.setStatus("OPEN");
        item.setSessionId(sessionId);
        item.setSeverity("HIGH");
        item.setTitle("test");
        return item;
    }

    private static ShoppingSession disputedSession(String sessionId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setState(SessionState.DISPUTED);
        return session;
    }

    private static OrderDto sampleOrder(String orderId, String sessionId) {
        return new OrderDto(orderId, sessionId, 1L, "DEV-1", 500, List.of(),
                "PAID", "BALANCE", null, 1000, 500, Instant.now());
    }
}
