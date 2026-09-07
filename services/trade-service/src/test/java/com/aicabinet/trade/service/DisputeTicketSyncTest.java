package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.CabinetOrder;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
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
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock UserInfoMapper userInfoRepository;
    @Mock OpsExceptionService opsExceptionService;
    @Mock FileAttachmentService fileAttachmentService;
    @Mock VideoArchiveService videoArchiveService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock DistributedLockService distributedLockService;

    private DisputeService service;

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputeRepository, disputeMessageRepository, sessionRepository, orderRepository,
                settlementService, new ObjectMapper(), minioVideoService, auditService, riskControlService,
                permissionService, merchantScopeService, merchantFeaturePackService, merchantPortalGuard, skuCatalogRepository,
                new DisputeSlaProperties(48, 12, null, false), userInfoRepository, opsExceptionService,
                fileAttachmentService, null, videoArchiveService, orderPaymentService, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L)))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(fileAttachmentService.listDisputeEvidence(anyString()))
                .thenReturn(List.of());
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

        when(disputeRepository.findByIdForUpdate("D-TEST-002")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-TEST-002")).thenReturn(Optional.of(session));
        when(settlementService.waiveAndRefund(eq(session), anyBoolean())).thenReturn(0);

        service.resolveTicket(10001L, "D-TEST-002",
                new ResolveDisputeRequest(List.of(), "WAIVE"));

        verify(opsExceptionService).resolveOpenForSession(eq(10001L), eq("S-TEST-002"),
                eq("争议结案(WAIVE)同步关闭异常"));
    }

    /** D1: KEEP 未入账争议单 → 无 charge；订单 DISPUTED+netPaid=0 → PENDING。 */
    @Test
    void resolveTicket_keep_unpaidDisputedOrder_alignsToPending_withoutCharge() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-KEEP-1");
        ticket.setSessionId("S-KEEP-1");
        ticket.setStatus("OPEN");
        ticket.setItems("[{\"skuId\":\"SKU-A\",\"quantity\":1}]");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-KEEP-1");
        session.setDeviceId("CAB-001");
        session.setState(SessionState.DISPUTED);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-KEEP-1");
        order.setSessionId("S-KEEP-1");
        order.setStatus("DISPUTED");
        order.setTotalAmountCents(700);

        when(disputeRepository.findByIdForUpdate("D-KEEP-1")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-KEEP-1")).thenReturn(Optional.of(session));
        when(orderRepository.findBySessionId("S-KEEP-1")).thenReturn(Optional.of(order));
        when(orderPaymentService.netCompletedCents("O-KEEP-1")).thenReturn(0);
        when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.resolveTicket(10001L, "D-KEEP-1",
                new ResolveDisputeRequest(List.of(), "KEEP"));

        assertEquals("KEEP", result.resolutionType());
        assertEquals(0, result.adjustmentCents());
        assertEquals("RESOLVED", ticket.getStatus());
        assertEquals(SessionState.COMPLETED, session.getState());
        assertEquals("PENDING", order.getStatus());
        verify(settlementService, never()).waiveAndRefund(any(), anyBoolean());
        verify(settlementService, never()).confirmDisputedItems(any(), any());
        verify(orderPaymentService, never()).chargeOrder(any());
        verify(opsExceptionService).resolveOpenForSession(eq(10001L), eq("S-KEEP-1"),
                eq("争议结案(KEEP)同步关闭异常"));
    }

    /** D6: 已退款订单禁止 reopen，防重复退。 */
    @Test
    void reopenTicket_refundedOrder_conflicts() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-REOPEN-1");
        ticket.setSessionId("S-REOPEN-1");
        ticket.setStatus("RESOLVED");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-REOPEN-1");
        session.setDeviceId("CAB-001");

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-REOPEN-1");
        order.setSessionId("S-REOPEN-1");
        order.setStatus("REFUNDED");

        when(disputeRepository.findById("D-REOPEN-1")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-REOPEN-1")).thenReturn(Optional.of(session));
        when(orderRepository.findBySessionId("S-REOPEN-1")).thenReturn(Optional.of(order));

        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> service.reopenTicket(10001L, "D-REOPEN-1", null));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getReason().contains("已退款"));
        verify(disputeRepository, never()).save(any());
    }

    /** D4: 商户不可 ADJUST。 */
    @Test
    void resolveAsMerchant_adjust_rejected() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-ADJ-1");
        ticket.setSessionId("S-ADJ-1");
        ticket.setStatus("OPEN");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-ADJ-1");
        session.setDeviceId("CAB-001");

        when(disputeRepository.findByIdForUpdate("D-ADJ-1")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-ADJ-1")).thenReturn(Optional.of(session));

        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> service.resolveAsMerchant(20001L, "D-ADJ-1",
                        new ResolveDisputeRequest(
                                List.of(new ResolveDisputeRequest.ManualLineItem("SKU-A", 1)),
                                "ADJUST")));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getReason().contains("商户结案仅支持"));
        verify(settlementService, never()).confirmDisputedItems(any(), any());
    }

    /** D4 附带：商户不可强抢他人认领。 */
    @Test
    void claimAsMerchant_cannotStealAssignee() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-CLAIM-M");
        ticket.setSessionId("S-CLAIM-M");
        ticket.setStatus("OPEN");
        ticket.setAssignee("运营甲");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-CLAIM-M");
        session.setDeviceId("CAB-001");

        when(disputeRepository.findByIdForUpdate("D-CLAIM-M")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-CLAIM-M")).thenReturn(Optional.of(session));
        when(userInfoRepository.findById(20002L)).thenReturn(Optional.empty());

        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> service.claimAsMerchant(20002L, "D-CLAIM-M"));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getReason().contains("认领"));
        verify(disputeRepository, never()).save(any());
    }

    /** D5: 运营可强抢已认领工单。 */
    @Test
    void claimTicket_opsCanOverrideAssignee() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-CLAIM-O");
        ticket.setSessionId("S-CLAIM-O");
        ticket.setStatus("OPEN");
        ticket.setAssignee("商户乙");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-CLAIM-O");
        session.setDeviceId("CAB-001");
        session.setState(SessionState.DISPUTED);

        com.aicabinet.trade.domain.UserInfo ops = new com.aicabinet.trade.domain.UserInfo();
        ops.setUserId(10001L);
        ops.setName("运营丙");

        when(disputeRepository.findByIdForUpdate("D-CLAIM-O")).thenReturn(Optional.of(ticket));
        when(sessionRepository.findById("S-CLAIM-O")).thenReturn(Optional.of(session));
        when(userInfoRepository.findById(10001L)).thenReturn(Optional.of(ops));
        when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.claimTicket(10001L, "D-CLAIM-O");

        assertEquals("运营丙", ticket.getAssignee());
        assertEquals("运营丙", dto.assignee());
        verify(disputeRepository).save(ticket);
    }
}
