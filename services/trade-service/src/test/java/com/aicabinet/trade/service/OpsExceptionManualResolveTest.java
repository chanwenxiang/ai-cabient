package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsExceptionManualResolveTest {

    @Mock OpsExceptionMapper repository;
    @Mock PermissionService permissionService;
    @Mock AdminAuditService auditService;
    @Mock AdminAuditLogMapper auditRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock SettlementService settlementService;
    @Mock DisputeService disputeService;

    private OpsExceptionService service;

    @BeforeEach
    void setUp() {
        service = new OpsExceptionService(repository, permissionService, auditService, auditRepository,
                sessionRepository, settlementService, disputeService);
    }

    @Test
    void manualResolveWaive_closesOpenDisputeTicket() {
        OpsException item = new OpsException();
        item.setExceptionId("EX-TEST-001");
        item.setExceptionType("RECOGNITION_FAILED");
        item.setStatus("OPEN");
        item.setSessionId("S-TEST-001");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-TEST-001");
        session.setState(SessionState.DISPUTED);

        when(repository.findById("EX-TEST-001")).thenReturn(Optional.of(item));
        when(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", "EX-TEST-001"))
                .thenReturn(List.of());
        when(sessionRepository.findById("S-TEST-001")).thenReturn(Optional.of(session));
        when(settlementService.waiveAndRefund(session)).thenReturn(0);

        service.manualResolve(10001L, "EX-TEST-001", "WAIVE", List.of(),
                "ops-waive-EX-TEST-001", "E2E test");

        verify(disputeService).closeOpenTicketForSession(eq(10001L), eq("S-TEST-001"), eq("WAIVE"), eq(List.of()));
    }
}
