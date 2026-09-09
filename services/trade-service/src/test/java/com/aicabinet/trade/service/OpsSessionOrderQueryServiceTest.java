package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
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
    @Mock MinioVideoService minioVideoService;
    @Mock PaymentService paymentService;
    @Mock RefundPolicyService refundPolicyService;

    private OpsSessionOrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new OpsSessionOrderQueryService(
                permissionService, merchantScopeService, sessionRepository, orderRepository,
                orderLineRepository, splitRepository, settlementService, auditService,
                minioVideoService, paymentService, refundPolicyService, new OrderViewAssembler());
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

    private static CabinetOrderLine line(String sku, String name, int qty, String batch) {
        CabinetOrderLine l = new CabinetOrderLine();
        l.setSkuId(sku);
        l.setSkuName(name);
        l.setQuantity(qty);
        l.setBatchNo(batch);
        return l;
    }
}
