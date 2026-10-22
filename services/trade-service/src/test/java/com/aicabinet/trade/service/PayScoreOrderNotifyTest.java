package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PayScoreOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PayScoreOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信支付分订单事件回调：DONE→PAID+流水 / USER_PAYING→提醒 / EXPIRED→升级催缴（CB-064）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayScoreOrderNotifyTest {

    @Mock PayScoreOrderMapper payScoreOrderMapper;
    @Mock CabinetOrderMapper cabinetOrderMapper;
    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock UnpaidDunningService unpaidDunningService;

    @InjectMocks PayScoreOrderNotifyService service;

    private PayScoreOrder po(String state, String orderId) {
        PayScoreOrder po = new PayScoreOrder();
        po.setOutOrderNo("1792659000000000000001");
        po.setOrderId(orderId);
        po.setUserId(10002L);
        po.setOrderState(state);
        po.setActualAmountCents(350);
        return po;
    }

    private CabinetOrder order(String status) {
        CabinetOrder o = new CabinetOrder();
        o.setOrderId("1792659779535535992400");
        o.setUserId(10002L);
        o.setStatus(status);
        o.setTotalAmountCents(350);
        return o;
    }

    @Test
    void doneEvent_marksPaidAndRecordsChargeAndClearsDebt() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);
        when(paymentOperationMapper.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE")
                .put("transaction_id", "WX-TF-1");
        service.handleOrderNotify(event);

        ArgumentCaptor<CabinetOrder> cap = ArgumentCaptor.forClass(CabinetOrder.class);
        verify(cabinetOrderMapper).updateById(cap.capture());
        assertEquals("PAID", cap.getValue().getStatus());
        assertEquals("WX-TF-1", cap.getValue().getPayTradeNo());
        ArgumentCaptor<com.aicabinet.trade.domain.PaymentOperation> opCap =
                ArgumentCaptor.forClass(com.aicabinet.trade.domain.PaymentOperation.class);
        verify(paymentOperationMapper).insert(opCap.capture());
        assertEquals("CHARGE", opCap.getValue().getOperationType());
        assertEquals(350, opCap.getValue().getAmountCents());
        verify(unpaidDunningService).onUnpaidCleared(10002L);
        // 幂等键与同步扣款同约定，回调重推/补偿都收敛
        assertEquals("CHARGE:1792659779535535992400:350", opCap.getValue().getIdempotencyKey());
    }

    @Test
    void userPayingEvent_keepsPendingAndReminds() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "USER_PAYING");
        service.handleOrderNotify(event);

        verify(unpaidDunningService).remindBySms(o, false);
        verify(unpaidDunningService, never()).recordUnpaidAndEscalate(any());
        verify(paymentOperationMapper, never()).insert(any(com.aicabinet.trade.domain.PaymentOperation.class));
    }

    @Test
    void expiredEvent_escalatesDunning() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "EXPIRED");
        service.handleOrderNotify(event);

        verify(unpaidDunningService).recordUnpaidAndEscalate(10002L);
        verify(paymentOperationMapper, never()).insert(any(com.aicabinet.trade.domain.PaymentOperation.class));
    }

    @Test
    void unmatchedOrderEvent_isSwallowed() {
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of());
        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "PSU-NOBODY")
                .put("state", "DONE");
        service.handleOrderNotify(event);
        verify(cabinetOrderMapper, never()).updateById(any(CabinetOrder.class));
        verify(paymentOperationMapper, never()).insert(any(com.aicabinet.trade.domain.PaymentOperation.class));
    }

    @Test
    void orderEventRouting_byOutOrderNoPresence() {
        var orderEvent = new ObjectMapper().createObjectNode().put("out_order_no", "x");
        var permEvent = new ObjectMapper().createObjectNode().put("out_request_no", "y");
        assertTrue(PayScoreOrderNotifyService.isOrderEvent(orderEvent));
        assertFalse(PayScoreOrderNotifyService.isOrderEvent(permEvent));
    }

    private static void assertFalse(boolean b) {
        org.junit.jupiter.api.Assertions.assertFalse(b);
    }
}
