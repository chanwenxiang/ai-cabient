package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeOrderRefundTest {

    static {
        // M01：reopenUpdateWrapper 用 LambdaUpdateWrapper（null 列更新），
        // 纯 Mockito 环境需手动初始化 TableInfo 缓存（存量坑，2026-10-01 补）
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                DisputeTicket.class);
    }

    @Mock DisputeTicketMapper disputeRepository;
    @Mock DisputeMessageMapper disputeMessageRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock SettlementService settlementService;
    @Mock MinioVideoService minioVideoService;
    @Mock JiangyiOrderVideoService jiangyiOrderVideoService;
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
    @Mock OrderPaymentService orderPaymentService;
    @Mock DistributedLockService distributedLockService;
    @Mock SessionService sessionService;
    @Mock CabinetMetrics metrics;

    private DisputeService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            ShoppingSession s = inv.getArgument(0);
            SessionState t = inv.getArgument(1);
            s.setState(t);
            return null;
        }).when(sessionService).transition(any(), any());
        service = new DisputeService(disputeRepository, disputeMessageRepository, sessionRepository, orderRepository,
                settlementService, new ObjectMapper(), minioVideoService, jiangyiOrderVideoService, auditService,
                riskControlService,
                permissionService, merchantScopeService, merchantFeaturePackService, merchantPortalGuard, skuCatalogRepository,
                new DisputeSlaProperties(48, 12, null, false), userInfoRepository, opsExceptionService,
                fileAttachmentService, null, orderPaymentService, distributedLockService, null, null,
                sessionService, metrics, null);
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

    // ── MA-154：消费者自助退款（钱会动的那条）的归属闸 ──────────────────────────
    //
    // 第一轮核过 `DisputeService#refundByConsumer` 有归属校验，但此前只钉过 operator 侧，
    // 消费者侧一条都没有——而这条的失败码是 404（刻意不泄露「订单号存在」）。

    @Test
    void refundByConsumer_foreignUser_notFoundAndNoMoneyMoves() {
        CabinetOrder other = new CabinetOrder();
        other.setOrderId("O-OWN-9");
        other.setUserId(20002L);
        other.setStatus("PAID");
        other.setTotalAmountCents(1200);
        when(orderRepository.findByIdForUpdate("O-OWN-9")).thenReturn(Optional.of(other));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundByConsumer(10001L, "O-OWN-9",
                        new OrderRefundRequest("替别人退款", null, false, null)));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode(),
                "他人订单要回 404 而不是 403——403 等于确认这个订单号存在");
        verify(settlementService, org.mockito.Mockito.never()).waiveAndRefund(any(), anyBoolean());
    }

    @Test
    void refundByConsumer_owner_isNotStoppedByOwnershipGate() {
        // 正向对照：本人**不**该被归属门挡。本类把退款策略/结算依赖留成 mock/null，
        // 本人路径会继续往下走并以别的错误结束——这里只断言「不是 404」，
        // 因为要钉的恰恰是「闸没写反成只放行非本人」，不关心后面的门长什么样。
        CabinetOrder mine = new CabinetOrder();
        mine.setOrderId("O-MINE-9");
        mine.setUserId(10001L);
        mine.setStatus("PAID");
        mine.setTotalAmountCents(1200);
        when(orderRepository.findByIdForUpdate("O-MINE-9")).thenReturn(Optional.of(mine));

        Throwable thrown = null;
        try {
            service.refundByConsumer(10001L, "O-MINE-9", new OrderRefundRequest("本人退款理由", null, false, null));
        } catch (Throwable t) {
            thrown = t;
        }
        if (thrown instanceof ResponseStatusException rse) {
            org.junit.jupiter.api.Assertions.assertNotEquals(org.springframework.http.HttpStatus.NOT_FOUND,
                    rse.getStatusCode(), "本人不得停在归属门");
        }
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

        var result = service.refundByOperator(10001L, "O-PART-1",
                new OrderRefundRequest(
                        "剩余行全退",
                        null,
                        null,
                        List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, true))));

        assertEquals("REFUNDED", result.status());
        assertEquals(200, result.refundedCents());
        assertEquals(SessionState.COMPLETED, session.getState());
        verify(sessionService).transition(session, SessionState.COMPLETED);
        verify(settlementService, never()).waiveAndRefund(any(), anyBoolean());
    }

    @Test
    void autoWaiveTicket_skipsWhenSessionHasOrder_zeroMoneyInvariant() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("T-AW-1");
        ticket.setSessionId("S-AW-1");
        ticket.setStatus("OPEN");
        ticket.setReason("识别超时，已转人工审核，本次暂未扣款");
        when(disputeRepository.findByIdForUpdate("T-AW-1")).thenReturn(java.util.Optional.of(ticket));
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-AW-1");
        when(sessionRepository.findById("S-AW-1")).thenReturn(java.util.Optional.of(session));
        // 会话挂有订单（哪怕 PENDING/PAID）⇒ 零资金不变量：一律留人工
        when(orderRepository.findBySessionId("S-AW-1"))
                .thenReturn(java.util.Optional.of(new CabinetOrder()));

        var result = service.autoWaiveTicket("T-AW-1");

        assertNull(result);
        assertEquals("OPEN", ticket.getStatus(), "有订单的票不得自动免单");
        verify(settlementService, never()).waiveAndRefund(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void autoWaiveTicket_noOrder_waivesAndMarksAutoNote() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("T-AW-2");
        ticket.setSessionId("S-AW-2");
        ticket.setStatus("OPEN");
        ticket.setReason("识别超时，已转人工审核，本次暂未扣款");
        when(disputeRepository.findByIdForUpdate("T-AW-2")).thenReturn(java.util.Optional.of(ticket));
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-AW-2");
        when(sessionRepository.findById("S-AW-2")).thenReturn(java.util.Optional.of(session));
        when(orderRepository.findBySessionId("S-AW-2")).thenReturn(java.util.Optional.empty());
        when(settlementService.waiveAndRefund(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(0);

        var result = service.autoWaiveTicket("T-AW-2");

        assertNotNull(result);
        assertEquals("RESOLVED", ticket.getStatus());
        assertEquals("AUTO_WAIVE", ticket.getOperatorNote());
        verify(settlementService, times(1)).waiveAndRefund(any(), org.mockito.ArgumentMatchers.eq(false));
    }
}
