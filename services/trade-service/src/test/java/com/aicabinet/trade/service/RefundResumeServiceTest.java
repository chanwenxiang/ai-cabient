package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundResumeServiceTest {

    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock CabinetOrderMapper orderRepository;
    @Mock OpsAlertDispatcher opsAlertDispatcher;
    @Mock SettlementWaiveRefundService waiveRefundService;
    @Mock SettlementPartialRefundService partialRefundService;

    private RefundResumeService service;

    @BeforeEach
    void setUp() {
        service = new RefundResumeService(paymentOperationMapper, orderRepository,
                opsAlertDispatcher, waiveRefundService, partialRefundService);
    }

    @Test
    void completedFullRefund_resumesInventoryCouponAndSplitFinalizerOnce() {
        RefundResumeService.OrderRefundContext context = RefundResumeService.fullContext(
                "O-1", true, 500, "全额退款");
        PaymentOperation operation = operation("O-1", context);
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-1");
        when(orderRepository.findById("O-1")).thenReturn(Optional.of(order));
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(operation));
        when(paymentOperationMapper.updateRefundContextJson(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertTrue(service.resumeOrderRefund(operation));

        verify(waiveRefundService).finalizeWaiveRefund(any(SettlementWaiveRefundService.WaiveRefundPrep.class));
        verify(partialRefundService, never()).resumeLocalPartialRefund(anyString(), any(), anyBoolean(), anyInt());
        verify(paymentOperationMapper).finalizeInitiatedRefundTrace(operation.getIdempotencyKey());
    }

    @Test
    void completedPartialRefund_resumesRequestedLinesWithoutCallingChannel() {
        List<OrderRefundRequest.PartialRefundLine> lines = List.of(
                new OrderRefundRequest.PartialRefundLine("SKU-1", 2, true));
        RefundResumeService.OrderRefundContext context = RefundResumeService.partialContext(
                "O-2", lines, true, 300, "按行退款");
        PaymentOperation operation = operation("O-2", context);
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-2");
        when(orderRepository.findById("O-2")).thenReturn(Optional.of(order));
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(operation));
        when(paymentOperationMapper.updateRefundContextJson(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertTrue(service.resumeOrderRefund(operation));

        verify(partialRefundService).resumeLocalPartialRefund("O-2", lines, true, 300);
        verify(waiveRefundService, never()).finalizeWaiveRefund(any());
    }

    @Test
    void legacyCompletedRefundLaterAbnormal_onlyTracesAndAlerts() {
        PaymentOperation operation = operation("O-3", null);
        operation.setStatus("COMPLETED");
        when(paymentOperationMapper.findByIdempotencyKey("REFUND:O-3:500:ABNORMAL"))
                .thenReturn(Optional.empty());

        assertTrue(service.recordLegacyAbnormalTrace(operation));

        verify(paymentOperationMapper).save(any(PaymentOperation.class));
        verify(paymentOperationMapper).markLegacyRefundAbnormal(
                operation.getOperationId(), operation.getReason(), "退款 [refund:ABNORMAL]");
        verify(opsAlertDispatcher).send(anyString(), anyString(), anyString(), any());
        verify(waiveRefundService, never()).finalizeWaiveRefund(any());
        verify(partialRefundService, never()).resumeLocalPartialRefund(anyString(), any(), anyBoolean(), anyInt());
    }

    private PaymentOperation operation(String orderId, RefundResumeService.OrderRefundContext context) {
        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId("OP-" + orderId);
        operation.setOrderId(orderId);
        operation.setOperationType("REFUND");
        operation.setStatus("COMPLETED");
        operation.setIdempotencyKey("REFUND:" + orderId + ":500");
        operation.setGatewayTradeNo("RF-" + orderId);
        operation.setReason("退款 [refund:PROCESSING]");
        operation.setRefundContextJson(RefundResumeService.orderContextJson(context));
        return operation;
    }
}
