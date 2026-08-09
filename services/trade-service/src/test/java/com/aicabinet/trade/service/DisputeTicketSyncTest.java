package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DisputeMessageMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeTicketSyncTest {

    @Mock DisputeTicketMapper disputeRepository;
    @Mock DisputeMessageMapper disputeMessageRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock SettlementService settlementService;
    @Mock MinioVideoService minioVideoService;
    @Mock AdminAuditService auditService;
    @Mock RiskControlService riskControlService;
    @Mock PermissionService permissionService;
    @Mock MerchantScopeService merchantScopeService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock UserInfoMapper userInfoRepository;
    @Mock OpsExceptionService opsExceptionService;
    @Mock VideoArchiveService videoArchiveService;

    private DisputeService service;

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputeRepository, disputeMessageRepository, sessionRepository, orderRepository,
                settlementService, new ObjectMapper(), minioVideoService, auditService, riskControlService,
                permissionService, merchantScopeService, null, merchantPortalGuard, skuCatalogRepository,
                new DisputeSlaProperties(48, 12, null, false), userInfoRepository, opsExceptionService,
                null, null, videoArchiveService);
    }

    @Test
    void closeOpenTicketForSession_waive_marksTicketResolved() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-TEST-001");
        ticket.setSessionId("S-TEST-001");
        ticket.setStatus("OPEN");
        ticket.setItems("[]");

        when(disputeRepository.findBySessionId("S-TEST-001")).thenReturn(Optional.of(ticket));

        service.closeOpenTicketForSession(10001L, "S-TEST-001", "WAIVE", null);

        ArgumentCaptor<DisputeTicket> captor = ArgumentCaptor.forClass(DisputeTicket.class);
        verify(disputeRepository).save(captor.capture());
        assertEquals("RESOLVED", captor.getValue().getStatus());
        assertEquals("[]", captor.getValue().getResolutionItems());
    }

    @Test
    void resolveTicket_waive_syncsOpenOpsExceptions() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-TEST-002");
        ticket.setSessionId("S-TEST-002");
        ticket.setStatus("OPEN");
        ticket.setItems("[]");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-TEST-002");
        session.setDeviceId("CAB-001");
        session.setState(SessionState.DISPUTED);

        when(disputeRepository.findById("D-TEST-002")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-TEST-002")).thenReturn(Optional.of(session));
        when(settlementService.waiveAndRefund(session)).thenReturn(0);

        service.resolveTicket(10001L, "D-TEST-002",
                new ResolveDisputeRequest(List.of(), "WAIVE"));

        verify(opsExceptionService).resolveOpenForSession(eq(10001L), eq("S-TEST-002"),
                eq("争议结案(WAIVE)同步关闭异常"));
    }
}
