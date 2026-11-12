package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsSessionOrderQueryServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantScopeService merchantScopeService;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock CabinetOrderLineMapper orderLineRepository;
    @Mock OrderRevenueSplitMapper splitRepository;
    @Mock SettlementService settlementService;
    @Mock AdminAuditService auditService;
    @Mock PaymentService paymentService;
    @Mock RefundPolicyService refundPolicyService;
    @Mock ConsumerPreauthService consumerPreauthService;
    @Mock DisputeService disputeService;

    private OpsSessionOrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new OpsSessionOrderQueryService(
                permissionService, merchantScopeService, sessionRepository, orderRepository,
                orderLineRepository, splitRepository, settlementService, auditService,
                paymentService, refundPolicyService, new OrderViewAssembler(), null,
                consumerPreauthService, disputeService);
    }

    @Test
    void buildAdminLineSummary_limitsAndSuffix() {
        CabinetOrderLine a = line("A", "牛奶", 1, null);
        CabinetOrderLine b = line("B", "面包", 2, "B1");
        CabinetOrderLine c = line("C", "水", 1, null);
        assertEquals("", OpsSessionOrderQueryService.buildAdminLineSummary(List.of()));
        assertEquals("牛奶 x1、面包 x2 @B1 等3件",
                OpsSessionOrderQueryService.buildAdminLineSummary(List.of(a, b, c)));
    }

    @Test
    void cancelSession_recognizing_conflicts() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-1");
        session.setDeviceId("CAB-001");
        session.setState(SessionState.RECOGNIZING);
        when(sessionRepository.findById("S-1")).thenReturn(Optional.of(session));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.cancelSession(10001L, "S-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("异常中心"));
        verify(sessionRepository, never()).save(session);
    }

    @Test
    void cancelSession_completed_conflicts() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-2");
        session.setDeviceId("CAB-001");
        session.setState(SessionState.COMPLETED);
        when(sessionRepository.findById("S-2")).thenReturn(Optional.of(session));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.cancelSession(10001L, "S-2"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.SESSION_FINISHED, ex.getReason());
    }

    @Test
    void stuckActiveStates_excludesTerminal() {
        assertTrue(OpsSessionOrderQueryService.STUCK_ACTIVE_STATES.contains(SessionState.WAITING_UPLOAD));
        assertTrue(OpsSessionOrderQueryService.STUCK_ACTIVE_STATES.contains(SessionState.SHOPPING));
        assertTrue(!OpsSessionOrderQueryService.STUCK_ACTIVE_STATES.contains(SessionState.COMPLETED));
        assertTrue(!OpsSessionOrderQueryService.STUCK_ACTIVE_STATES.contains(SessionState.CANCELLED));
        assertTrue(!OpsSessionOrderQueryService.STUCK_ACTIVE_STATES.contains(SessionState.FAILED));
        assertTrue(!OpsSessionOrderQueryService.STUCK_ACTIVE_STATES.contains(SessionState.DISPUTED));
    }

    /**
     * R20 P2-14：按行导出必须**一次**批量取行，不能逐单查（原来一页 EXPORT_LIMIT 单就是同样多次查询）。
     * 同时校验 CSV 行序仍按 line id ASC（批量取内建排序，与逐单版同序——导出产物顺序变了就是 diff 变了）。
     */
    @Test
    void exportOrdersCsv_byLines_fetchesAllLinesInOneQueryAndKeepsOrder() {
        CabinetOrder o1 = order("O-1");
        CabinetOrder o2 = order("O-2");
        when(merchantScopeService.intersectDeviceFilter(anyLong(), any())).thenReturn(null);
        when(orderRepository.findByFiltersOrderByCreatedAtDesc(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(o1, o2)));
        when(orderLineRepository.findByOrderIds(anyCollection())).thenReturn(List.of(
                exportLine("O-1", 1L, "SKU-A", "可乐", 2, 500),
                exportLine("O-1", 2L, "SKU-B", "薯片", 1, 800),
                exportLine("O-2", 3L, "SKU-A", "可乐", 3, 500)));

        byte[] csv = service.exportOrdersCsv(10001L, new OpsSessionOrderQueryService.OrderExportQuery(
                "CAB-001", null, "lines", null, null, null, null, null, null, null, null, false));

        String text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("O-1,CAB-001,PAID,SKU-A,可乐,2,500,1000"), text);
        assertTrue(text.contains("O-1,CAB-001,PAID,SKU-B,薯片,1,800,800"), text);
        assertTrue(text.contains("O-2,CAB-001,PAID,SKU-A,可乐,3,500,1500"), text);
        assertTrue(text.indexOf("SKU-A,可乐,2") < text.indexOf("SKU-B,薯片"),
                "同一订单的行必须先出现 line id 更小的那条");
        verify(orderLineRepository, never()).findByOrderId(anyString());
        verify(orderLineRepository, times(1)).findByOrderIds(anyCollection());
    }

    private static CabinetOrder order(String orderId) {
        CabinetOrder o = new CabinetOrder();
        o.setOrderId(orderId);
        o.setDeviceId("CAB-001");
        o.setStatus("PAID");
        o.setCreatedAt(Instant.parse("2026-11-01T02:00:00Z"));
        return o;
    }

    private static CabinetOrderLine exportLine(String orderId, Long id, String sku, String name,
                                               int qty, int unitCents) {
        CabinetOrderLine l = new CabinetOrderLine();
        l.setId(id);
        l.setOrderId(orderId);
        l.setSkuId(sku);
        l.setSkuName(name);
        l.setQuantity(qty);
        l.setUnitPriceCents(unitCents);
        l.setLineAmountCents(qty * unitCents);
        return l;
    }

    private static CabinetOrderLine line(String sku, String name, int qty, String batch) {
        CabinetOrderLine l = new CabinetOrderLine();
        l.setSkuId(sku);
        l.setSkuName(name);
        l.setQuantity(qty);
        l.setBatchNo(batch);
        return l;
    }
}
