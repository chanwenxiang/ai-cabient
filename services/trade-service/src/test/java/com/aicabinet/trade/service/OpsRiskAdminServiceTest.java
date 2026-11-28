package com.aicabinet.trade.service;

import com.aicabinet.common.dto.RiskEventDto;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.mapper.RiskEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsRiskAdminServiceTest {

    @Mock PermissionService permissionService;
    @Mock RiskControlService riskControlService;
    @Mock RiskEventMapper riskEventRepository;
    @Mock com.aicabinet.trade.mapper.UserBlacklistMapper blacklistRepository;
    @Mock AdminAuditService auditService;
    private OpsRiskAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsRiskAdminService(permissionService, riskControlService,
                riskEventRepository, blacklistRepository, auditService);
    }

    private RiskEvent event(String severity) {
        RiskEvent event = new RiskEvent();
        event.setEventId(47L);
        event.setSeverity(severity);
        event.setDispositionStatus("OPEN");
        event.setCreatedAt(Instant.now().minusSeconds(60));
        return event;
    }

    @ParameterizedTest
    @ValueSource(strings = {"INFO", "WARN", "BLOCK"})
    void dispose_openSupportedSeverity_recordsOperatorTimeNoteAndAudit(String severity) {
        RiskEvent event = event(severity);
        when(riskEventRepository.findByIdForUpdate(47L)).thenReturn(Optional.of(event));

        RiskEventDto dto = service.disposeRiskEvent(9L, 47L, "  已复核，人工确认  ");

        assertEquals("ACKED", event.getDispositionStatus());
        assertNotNull(event.getDispositionAt());
        assertEquals("已复核，人工确认", event.getDispositionNote());
        assertEquals(9L, event.getDispositionOperatorId());
        assertEquals(9L, dto.dispositionOperatorId());
        verify(permissionService).requirePermission(9L, "ops:risk:blacklist");
        verify(riskEventRepository).updateById(event);
        verify(auditService).appendLog(9L, "RISK_EVENT_DISPOSE", "RISK_EVENT", "47",
                "级别=" + severity + " 备注=已复核，人工确认");
    }

    @Test
    void dispose_alreadyHandled_rejectedWithoutWrite() {
        RiskEvent event = event("WARN");
        event.setDispositionStatus("ACKED");
        when(riskEventRepository.findByIdForUpdate(47L)).thenReturn(Optional.of(event));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.disposeRiskEvent(9L, 47L, "备注"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(riskEventRepository, never()).updateById(org.mockito.ArgumentMatchers.any());
        verify(auditService, never()).appendLog(org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dispose_unsupportedSeverity_rejected() {
        when(riskEventRepository.findByIdForUpdate(47L)).thenReturn(Optional.of(event("CRITICAL")));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.disposeRiskEvent(9L, 47L, "备注")).getStatusCode());
    }

    @Test
    void dispose_blankOrTooLongNote_rejectedBeforeLock() {
        for (String note : new String[]{" ", "x".repeat(257)}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> service.disposeRiskEvent(9L, 47L, note)).getStatusCode());
        }
        verify(riskEventRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispose_transactionAndAuditAreOnSameServiceBoundary() throws Exception {
        assertNotNull(OpsRiskAdminService.class.getMethod("disposeRiskEvent",
                Long.class, Long.class, String.class).getAnnotation(
                        org.springframework.transaction.annotation.Transactional.class));
    }

    @Test
    void dispose_missingEvent_returns404() {
        when(riskEventRepository.findByIdForUpdate(47L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.disposeRiskEvent(9L, 47L, "备注")).getStatusCode());
    }

    @Test
    void runtimeConfig_reusesSystemConfigEditPermission() {
        service.updateRiskRuntimeConfig(9L, 5);
        verify(permissionService).requirePermission(9L, "ops:config:edit");
        verify(riskControlService).updateMaxDisputesPer7Days(9L, 5);
    }
}
