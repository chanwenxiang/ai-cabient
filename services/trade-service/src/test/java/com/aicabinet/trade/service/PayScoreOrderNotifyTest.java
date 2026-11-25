package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PayScoreOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PayScoreOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 微信支付分订单事件回调：DONE→PAID+流水 / USER_PAYING→提醒 / EXPIRED→升级催缴（CB-064）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayScoreOrderNotifyTest {

    @BeforeEach
    void registerLambdaCache() {
        // 纯单测里没人初始化 MyBatis-Plus 的 lambda 列缓存：handleOrderNotify 与
        // MA-275 的 CAS 都用 Wrappers/LambdaQueryWrapper 引用 PayScoreOrder 的方法引用。
        // 之前全量能绿是靠「某个 @SpringBootTest 先跑过」的顺序依赖假绿（单独跑必红）。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                PayScoreOrder.class);
        // 默认让状态 CAS 成功；竞态/0 行用例在各自测试内覆盖返回值。
        org.mockito.Mockito.lenient().when(payScoreOrderMapper.update(any(), any())).thenReturn(1);
    }

    @Mock PayScoreOrderMapper payScoreOrderMapper;
    @Mock CabinetOrderMapper cabinetOrderMapper;
    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock UnpaidDunningService unpaidDunningService;
    @Mock OpsExceptionService opsExceptionService;
    /** R20-2：DONE 是本单的分账/商户钱包入账时点（结算时未实收，只建了分账行）。 */
    @Mock RevenueSplitService revenueSplitService;
    /** R20-2：会员消费统计/返积分与 order_paid 通知同样挪到实收确认点。 */
    @Mock MemberService memberService;
    @Mock NotificationService notificationService;

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
        when(cabinetOrderMapper.updateById(o)).thenReturn(1);
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
        // R20-2：钱在这里才真的进来 ⇒ 分账入账必须跟着在这里发生（recordSplit 幂等，重推无害）
        verify(revenueSplitService).recordSplit(o);
        // R20-2：结算时被推迟的两腿在此补做，基数与结算路径同口径（订单总额，非本次实收额）
        verify(memberService).onOrderPaid(10002L, 350, o.getOrderId());
        verify(notificationService).notifyConsumer(
                eq(10002L), eq("order_paid"), any(), eq("ORDER"), eq(o.getOrderId()));
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
        // R20-2：微信最终没扣到钱 ⇒ 这条腿既不改订单状态也不触发入账，商户份额永不进钱包
        // （全仓无 SPLIT 反冲路径，所以「不入」就是唯一的正确处置）
        verify(revenueSplitService, never()).recordSplit(any(CabinetOrder.class));
        verifyNoInteractions(memberService, notificationService);
        assertEquals("PENDING", o.getStatus());
    }

    /**
     * R20-8：终态订单（REFUNDED/CANCELLED/PARTIAL_REFUNDED）收到迟到 DONE —— 只留 HIGH 痕迹，
     * 不得回写成 PAID（否则 DisputeService 只挡 REFUNDED 的旧实现还能二次退款）。
     */
    @Test
    void doneEvent_onTerminalOrder_doesNotResurrect() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("REFUNDED");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE")
                .put("transaction_id", "WX-TF-9");
        service.handleOrderNotify(event);

        verify(cabinetOrderMapper, never()).updateById(any(CabinetOrder.class));
        verify(paymentOperationMapper, never()).insert(any(com.aicabinet.trade.domain.PaymentOperation.class));
        verify(opsExceptionService).report(eq("PAYSCORE_DONE_ON_TERMINAL_ORDER"), eq("HIGH"), any(), any(), any());
        assertEquals("REFUNDED", o.getStatus());
    }

    /**
     * R20-P2-5（2026-11-12）：扣除成功（DONE）之后，微信仍可能异步补投 USER_PAYING/EXPIRED。
     * 旧实现把这两种状态当「未收到钱」处理 ⇒ 已扣款用户被记欠款 + 升级催缴 + 被欠款闸拦下一次开门。
     */
    @Test
    void expiredEvent_afterDone_doesNotEscalateNorRegressState() {
        PayScoreOrder po = po("DONE", "1792659779535535992400");
        CabinetOrder o = order("PAID");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "EXPIRED");
        service.handleOrderNotify(event);

        verify(unpaidDunningService, never()).recordUnpaidAndEscalate(any());
        verify(unpaidDunningService, never()).remindBySms(any(), anyBoolean());
        // 快照不得从 DONE 倒退成 EXPIRED（查单/对账据此判断钱到没到）
        verify(payScoreOrderMapper, never()).updateById(any(PayScoreOrder.class));
        assertEquals("DONE", po.getOrderState());
    }

    @Test
    void userPayingEvent_afterDone_doesNotRemind() {
        PayScoreOrder po = po("DONE", "1792659779535535992400");
        CabinetOrder o = order("PAID");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "USER_PAYING");
        service.handleOrderNotify(event);

        verify(unpaidDunningService, never()).remindBySms(any(), anyBoolean());
        verify(unpaidDunningService, never()).recordUnpaidAndEscalate(any());
        verify(payScoreOrderMapper, never()).updateById(any(PayScoreOrder.class));
    }

    /**
     * 第二层判据：即便快照因历史脏数据不是 DONE，只要**订单实收** PAID，迟到欠款事件也不得催缴。
     */
    @Test
    void revokedEvent_whenOrderAlreadyPaid_doesNotEscalate() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PAID");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "REVOKED");
        service.handleOrderNotify(event);

        verify(unpaidDunningService, never()).recordUnpaidAndEscalate(any());
        assertEquals("PAID", o.getStatus());
    }

    // ── MA-275：订单态 CAS —— 并发通知不得把 DONE 覆盖回催缴态 ──────────────────

    /**
     * 并发竞态：EXPIRED 投递的快照读在 DONE 落库之前，入口判据没拦住；CAS 抢失败后
     * 重读现值=DONE ⇒ paid-evidence 判据兜住，不得催缴、不得倒退快照。
     */
    @Test
    void expiredEvent_casLostToConcurrentDone_dropsDunningAndKeepsDone() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);
        when(payScoreOrderMapper.update(any(), any())).thenReturn(0);
        PayScoreOrder fresh = po("DONE", "1792659779535535992400");
        when(payScoreOrderMapper.selectById(any())).thenReturn(fresh);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "EXPIRED");
        service.handleOrderNotify(event);

        verify(unpaidDunningService, never()).recordUnpaidAndEscalate(any());
        verify(payScoreOrderMapper, never()).updateById(any(PayScoreOrder.class));
        assertEquals("DONE", fresh.getOrderState());
    }

    /**
     * 自愈：EXPIRED 抢先落库记了欠款后，迟到的 DONE 仍能把状态 CAS 回 DONE 并入账
     *（单向「只进不退」对 DONE 这一侧是允许回头的——钱到了是权威）。
     */
    @Test
    void doneEvent_afterConcurrentExpire_retriesCasAndMarksPaid() {
        PayScoreOrder po = po("EXPIRED", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);
        when(payScoreOrderMapper.update(any(), any())).thenReturn(0, 1);
        when(cabinetOrderMapper.updateById(o)).thenReturn(1);
        PayScoreOrder fresh = po("EXPIRED", "1792659779535535992400");
        fresh.setActualAmountCents(350);
        when(payScoreOrderMapper.selectById(any())).thenReturn(fresh);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE");
        service.handleOrderNotify(event);

        // 第二轮 CAS 命中 ⇒ 业务分支照常执行（DONE → 入账/分账链）。
        // CAS 成功那轮的工作对象是重读出的 fresh（po 已被替换），断言落在 fresh 上。
        verify(paymentOperationMapper).insert(any(com.aicabinet.trade.domain.PaymentOperation.class));
        assertEquals("DONE", fresh.getOrderState());
    }

    /** 同态重复投递：CAS 影响行数 0 但现值已是目标态 ⇒ 幂等退出，业务分支不重复执行。 */
    @Test
    void duplicateDoneDelivery_casZeroButFreshEqualsTarget_idempotentExit() {
        PayScoreOrder po = po("DONE", "1792659779535535992400");
        CabinetOrder o = order("PAID");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);
        when(payScoreOrderMapper.update(any(), any())).thenReturn(0);
        PayScoreOrder fresh = po("DONE", "1792659779535535992400");
        when(payScoreOrderMapper.selectById(any())).thenReturn(fresh);

        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE");
        service.handleOrderNotify(event);

        verify(paymentOperationMapper, never()).insert(any(com.aicabinet.trade.domain.PaymentOperation.class));
        verify(payScoreOrderMapper, never()).updateById(any(PayScoreOrder.class));
    }

    @Test
    void doneEvent_orderStatusUpdateZero_throwsSoControllerCanReleaseNonce() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);
        when(payScoreOrderMapper.update(any(), any())).thenReturn(1);
        when(cabinetOrderMapper.updateById(o)).thenReturn(0);
        when(paymentOperationMapper.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE");

        assertThrows(IllegalStateException.class, () -> service.handleOrderNotify(event));

        verify(revenueSplitService, never()).recordSplit(any());
        verify(unpaidDunningService, never()).onUnpaidCleared(anyLong());
    }

    @Test
    void stateCasExhausted_throwsInsteadOfConfirmingUnprocessedNotification() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        CabinetOrder o = order("PENDING");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(cabinetOrderMapper.selectById("1792659779535535992400")).thenReturn(o);
        when(payScoreOrderMapper.update(any(), any())).thenReturn(0);
        when(payScoreOrderMapper.selectById(any())).thenReturn(
                po("USER_CONFIRM", "1792659779535535992400"),
                po("DOING", "1792659779535535992400"),
                po("CREATED", "1792659779535535992400"));
        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE");

        assertThrows(IllegalStateException.class, () -> service.handleOrderNotify(event));

        verify(payScoreOrderMapper, org.mockito.Mockito.times(3)).update(any(), any());
        verify(cabinetOrderMapper, never()).updateById(any());
        verify(paymentOperationMapper, never()).insert(any());
    }

    @Test
    void stateCasReadMissing_throwsInsteadOfBlindUpsert() {
        PayScoreOrder po = po("USER_PAYING", "1792659779535535992400");
        when(payScoreOrderMapper.selectList(any())).thenReturn(List.of(po));
        when(payScoreOrderMapper.update(any(), any())).thenReturn(0);
        when(payScoreOrderMapper.selectById(any())).thenReturn(null);
        var event = new ObjectMapper().createObjectNode()
                .put("out_order_no", "1792659000000000000001")
                .put("state", "DONE");

        assertThrows(IllegalStateException.class, () -> service.handleOrderNotify(event));

        verify(payScoreOrderMapper, never()).updateById(any());
        verify(paymentOperationMapper, never()).insert(any());
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
        var permEvent = new ObjectMapper().createObjectNode().put("authorization_code", "y");
        assertTrue(PayScoreOrderNotifyService.isOrderEvent(orderEvent));
        assertFalse(PayScoreOrderNotifyService.isOrderEvent(permEvent));
    }

    private static void assertFalse(boolean b) {
        org.junit.jupiter.api.Assertions.assertFalse(b);
    }
}
