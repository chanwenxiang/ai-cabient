package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.recognition.RecognizedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3b 拆段的边界契约：渠道扣款在事务外，痕迹收口必须晚于 finalize 成功。
 * 钉住「finalize 失败时绝不调用 finalizeChargePending」——否则会留下 FINALIZED 但没有
 * 本地记账的不可见缺口（F3a 巡检也捞不到）。
 */
@ExtendWith(MockitoExtension.class)
class SettlementSplitBoundaryTest {

    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock CabinetOrderLineMapper orderLineRepository;
    @Mock DeviceValidationService deviceValidationService;
    @Mock InventoryService inventoryService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock UserValidationService userValidationService;
    @Mock CouponService couponService;
    @Mock MemberService memberService;
    @Mock RevenueSplitService revenueSplitService;
    @Mock NotificationService notificationService;
    @Mock DisplaySnapshotHelper displaySnapshotHelper;
    @Mock SettlementOrderSupport orderSupport;
    @Mock OpsExceptionService opsExceptionService;
    @Mock RiskControlService riskControlService;

    SettlementOrderFinalizeService service;

    @BeforeEach
    void setUp() {
        service = new SettlementOrderFinalizeService(
                sessionRepository, orderRepository, orderLineRepository, deviceValidationService,
                inventoryService, orderPaymentService, userValidationService, couponService,
                memberService, revenueSplitService, notificationService, displaySnapshotHelper,
                orderSupport, opsExceptionService, riskControlService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(couponService.selectPreferredOrBest(any(), any(), anyInt(), any()))
                .thenReturn(Optional.empty());
        lenient().when(inventoryService.deductForOrder(anyString(), anyList(), anyString()))
                .thenReturn(Map.of());
    }

    private ShoppingSession session() {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId("S-SPLIT");
        s.setUserId(7L);
        s.setDeviceId("CAB-1");
        return s;
    }

    @Test
    void finalizeFailsAfterCharge_traceStaysPending() {
        when(orderRepository.findBySessionId("S-SPLIT")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("split down"))
                .when(revenueSplitService).recordSplit(any());

        assertThrows(IllegalStateException.class, () -> service.finalizeOrder(
                session(), List.of(new RecognizedItem("SKU-A", 1, 1f))));

        verify(orderPaymentService).chargeOrder(any());
        verify(orderPaymentService, never()).finalizeChargePending(anyString(), any());
    }

    @Test
    void chargeInsufficientBalance_marksPendingAndKeepsTraceForReconcile() {
        when(orderRepository.findBySessionId("S-SPLIT")).thenReturn(Optional.empty());
        doThrow(new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "余额不足"))
                .when(orderPaymentService).chargeOrder(any());

        service.finalizeOrder(session(), List.of(new RecognizedItem("SKU-A", 1, 1f)));

        org.mockito.ArgumentCaptor<CabinetOrder> captor =
                org.mockito.ArgumentCaptor.forClass(CabinetOrder.class);
        verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        verify(orderPaymentService, never()).finalizeChargePending(anyString(), any());
        verify(riskControlService).onUnpaidOrderCreated(eq(7L), eq("CAB-1"),
                anyString(), eq("CHARGE_FAILED"), anyLong());
    }

    @Test
    void chargeSuccess_finalizeCommitsThenTraceFinalized() {
        when(orderRepository.findBySessionId("S-SPLIT")).thenReturn(Optional.empty());

        service.finalizeOrder(session(), List.of(new RecognizedItem("SKU-A", 1, 1f)));

        verify(orderPaymentService).finalizeChargePending(anyString(), any());
    }
}
