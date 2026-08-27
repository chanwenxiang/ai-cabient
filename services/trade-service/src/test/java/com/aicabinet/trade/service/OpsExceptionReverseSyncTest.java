package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsExceptionReverseSyncTest {

    @Mock OpsExceptionMapper repository;
    @Mock PermissionService permissionService;
    @Mock AdminAuditService auditService;
    @Mock AdminAuditLogMapper auditRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock SettlementService settlementService;
    @Mock DisputeService disputeService;
    @Mock RepairTicketService repairTicketService;
    @Mock DistributedLockService distributedLockService;

    private OpsExceptionService service;

    @BeforeEach
    void setUp() {
        service = new OpsExceptionService(repository, permissionService, auditService, auditRepository,
                sessionRepository, settlementService, disputeService, repairTicketService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    @Test
    void resolveOpenForSession_closesAllOpenExceptions() {
        OpsException open = new OpsException();
        open.setExceptionId("EX-OPEN-001");
        open.setSessionId("S-TEST-001");
        open.setStatus("OPEN");

        OpsException processing = new OpsException();
        processing.setExceptionId("EX-PROC-001");
        processing.setSessionId("S-TEST-001");
        processing.setStatus("PROCESSING");

        when(repository.findBySessionIdAndStatusIn(eq("S-TEST-001"), any()))
                .thenReturn(List.of(open, processing));
        when(repository.findByIdForUpdate("EX-OPEN-001")).thenReturn(java.util.Optional.of(open));
        when(repository.findByIdForUpdate("EX-PROC-001")).thenReturn(java.util.Optional.of(processing));

        service.resolveOpenForSession(10001L, "S-TEST-001", "争议结案(WAIVE)同步关闭异常");

        ArgumentCaptor<OpsException> captor = ArgumentCaptor.forClass(OpsException.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        for (OpsException saved : captor.getAllValues()) {
            assertEquals("RESOLVED", saved.getStatus());
            assertEquals(10001L, saved.getAssigneeUserId());
            assertEquals("争议结案(WAIVE)同步关闭异常", saved.getResolution());
        }
        verify(auditService).appendLog(eq(10001L), eq("OPS_EXCEPTION_SYNC_FROM_DISPUTE"),
                eq("OPS_EXCEPTION"), eq("EX-OPEN-001"), eq("争议结案(WAIVE)同步关闭异常"));
        verify(auditService).appendLog(eq(10001L), eq("OPS_EXCEPTION_SYNC_FROM_DISPUTE"),
                eq("OPS_EXCEPTION"), eq("EX-PROC-001"), eq("争议结案(WAIVE)同步关闭异常"));
    }
}
