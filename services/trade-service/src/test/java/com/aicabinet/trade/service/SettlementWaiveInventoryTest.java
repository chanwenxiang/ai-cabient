package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I3 / 对照 I4：PENDING/已扣库订单 WAIVE 回库 vs 仅退款不回库。
 */
@ExtendWith(MockitoExtension.class)
class SettlementWaiveInventoryTest {

    @Mock CabinetOrderMapper orderRepository;
    @Mock InventoryService inventoryService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock RevenueSplitService revenueSplitService;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock DistributedLockService distributedLockService;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                sessionRepository, null, orderRepository, null,
                null, null, null, revenueSplitService,
                null, null, inventoryService, orderPaymentService,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, distributedLockService, null, null,
                new com.aicabinet.trade.service.view.OrderViewAssembler());
        org.springframework.test.util.ReflectionTestUtils.setField(settlementService, "self", settlementService);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        lenient().when(sessionRepository.findByIdForUpdate(anyString())).thenAnswer(inv -> {
            ShoppingSession s = new ShoppingSession();
            s.setSessionId(inv.getArgument(0));
            return Optional.of(s);
        });
    }

    /** I3: WAIVE 默认回库 → restoreForOrder，不写 REFUND_KEPT。 */
    @Test
    void waiveAndRefund_restoreTrue_restoresInventory_notKeptGoods() {
        ShoppingSession session = session("S-I3");
        CabinetOrder order = deductedOrder("O-I3", "S-I3");

        when(orderRepository.findBySessionId("S-I3")).thenReturn(Optional.of(order));
        when(orderPaymentService.netCompletedCents("O-I3")).thenReturn(0);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(inventoryService).restoreForOrder(anyString(), anyList(), anyMap());
        doNothing().when(revenueSplitService).voidSplitOnFullRefund("O-I3");

        int refunded = settlementService.waiveAndRefund(session, true);

        assertEquals(0, refunded);
        assertFalse(order.isInventoryDeducted());
        assertEquals("REFUNDED", order.getStatus());
        verify(inventoryService).restoreForOrder(
                eq("CAB-001"),
                argThat(items -> items.size() == 1
                        && "SKU-A".equals(items.get(0).skuId())
                        && items.get(0).quantity() == 2),
                argThat(batches -> "BATCH-A".equals(batches.get("SKU-A"))));
        verify(inventoryService, never()).recordRefundKeptGoods(anyString(), anyList(), anyMap(), anyString());
        verify(revenueSplitService).voidSplitOnFullRefund("O-I3");
    }

    /** I4 对照: restore=false → REFUND_KEPT，保持 inventoryDeducted。 */
    @Test
    void waiveAndRefund_restoreFalse_recordsKeptGoods_keepsDeductedFlag() {
        ShoppingSession session = session("S-I4");
        CabinetOrder order = deductedOrder("O-I4", "S-I4");

        when(orderRepository.findBySessionId("S-I4")).thenReturn(Optional.of(order));
        when(orderPaymentService.netCompletedCents("O-I4")).thenReturn(0);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(inventoryService).recordRefundKeptGoods(anyString(), anyList(), anyMap(), anyString());
        doNothing().when(revenueSplitService).voidSplitOnFullRefund("O-I4");

        settlementService.waiveAndRefund(session, false);

        org.junit.jupiter.api.Assertions.assertTrue(order.isInventoryDeducted());
        verify(inventoryService).recordRefundKeptGoods(
                eq("CAB-001"),
                anyList(),
                argThat(batches -> "BATCH-A".equals(batches.get("SKU-A"))),
                eq("O-I4"));
        verify(inventoryService, never()).restoreForOrder(anyString(), anyList(), anyMap());
    }

    private static ShoppingSession session(String id) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(id);
        return s;
    }

    private static CabinetOrder deductedOrder(String orderId, String sessionId) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setSessionId(sessionId);
        order.setDeviceId("CAB-001");
        order.setUserId(10001L);
        order.setTotalAmountCents(700);
        order.setStatus("PENDING");
        order.setInventoryDeducted(true);
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId("SKU-A");
        line.setSkuName("A");
        line.setQuantity(2);
        line.setUnitPriceCents(350);
        line.setLineAmountCents(700);
        line.setBatchNo("BATCH-A");
        order.setLines(new ArrayList<>(List.of(line)));
        return order;
    }
}
