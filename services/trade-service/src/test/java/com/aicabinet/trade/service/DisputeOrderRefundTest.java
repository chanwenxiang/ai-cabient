package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeOrderRefundTest {

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

    /** D8: 已结案工单上全额退款 → 同票 reopen 再 RESOLVED；只退一次。 */
    @Test
    void refundByOperator_full_reopensResolvedTicket_thenBlocksSecondRefund() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-FULL-1");
        order.setSessionId("S-FULL-1");
        order.setUserId(90001L);
        order.setDeviceId("CAB-001");
        order.setStatus("PAID");
        order.setTotalAmountCents(500);
        order.setPayChannel("BALANCE");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-FULL-1");
        session.setDeviceId("CAB-001");
        session.setUserId(90001L);
        session.setState(SessionState.COMPLETED);

        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D-FULL-1");
        ticket.setSessionId("S-FULL-1");
        ticket.setStatus("RESOLVED");
        ticket.setCategory("RECOGNITION");

        when(orderRepository.findByIdForUpdate("O-FULL-1")).thenReturn(Optional.of(order));
        when(sessionRepository.findById("S-FULL-1")).thenReturn(Optional.of(session));
        when(disputeRepository.findBySessionId("S-FULL-1")).thenReturn(Optional.of(ticket));
        when(settlementService.waiveAndRefund(session, false)).thenAnswer(inv -> {
            order.setStatus("REFUNDED");
            return 500;
        });
        when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var first = service.refundByOperator(10001L, "O-FULL-1",
                new OrderRefundRequest("误识别多扣款", null, false, null));

        assertEquals("D-FULL-1", first.ticketId());
        assertEquals("REFUNDED", first.status());
        assertEquals(500, first.refundedCents());
        assertEquals("RESOLVED", ticket.getStatus());
        assertEquals("USER_APPEAL", ticket.getCategory());
        assertNotNull(ticket.getReopenedAt());
        assertEquals(SessionState.COMPLETED, session.getState());
        verify(settlementService, times(1)).waiveAndRefund(session, false);

        ResponseStatusException second = assertThrows(ResponseStatusException.class,
                () -> service.refundByOperator(10001L, "O-FULL-1",
                        new OrderRefundRequest("再次全额退款", null, false, null)));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, second.getStatusCode());
        assertTrue(second.getReason().contains("已退款"));
        verify(settlementService, times(1)).waiveAndRefund(any(), anyBoolean());
    }

    /** D9: 部分退至 REFUNDED → session COMPLETED。 */
    @Test
    void refundByOperator_partialToRefunded_completesSession() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-PART-1");
        order.setSessionId("S-PART-1");
        order.setUserId(90001L);
        order.setDeviceId("CAB-001");
        order.setStatus("PARTIAL_REFUNDED");
        order.setTotalAmountCents(200);
        order.setPayChannel("BALANCE");

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-PART-1");
        session.setDeviceId("CAB-001");
        session.setState(SessionState.DISPUTED);

        when(orderRepository.findByIdForUpdate("O-PART-1")).thenReturn(Optional.of(order));
        when(sessionRepository.findById("S-PART-1")).thenReturn(Optional.of(session));
        when(settlementService.partialRefund(eq(order), anyList(), eq(false), eq("剩余行全退")))
                .thenReturn(new SettlementService.PartialRefundResult(200, "REFUNDED", true));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.refundByOperator(10001L, "O-PART-1",
                new OrderRefundRequest(
                        "剩余行全退",
                        null,
                        null,
                        List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, true))));

        assertEquals("REFUNDED", result.status());
        assertEquals(200, result.refundedCents());
        assertEquals(SessionState.COMPLETED, session.getState());

        ArgumentCaptor<ShoppingSession> sessionCaptor = ArgumentCaptor.forClass(ShoppingSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertEquals(SessionState.COMPLETED, sessionCaptor.getValue().getState());
        verify(settlementService, never()).waiveAndRefund(any(), anyBoolean());
    }
}
