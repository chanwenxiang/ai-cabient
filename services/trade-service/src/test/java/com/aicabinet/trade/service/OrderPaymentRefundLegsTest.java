package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-161 A 档：组合单退款拆腿判据的钉测。
 *
 * <p>全是**非零**金额——0/NULL 的样例证不出「渠道腿被超退」这个真事故（lessons #money-proof：
 * 金额必须用数字挣）。核心反例就是 MA-161 的现场：钱包冲抵 60 + 微信实收 40、全额退 100 时，
 * 绝不能把 100 压给只收 40 的微信交易。</p>
 */
class OrderPaymentRefundLegsTest {

    private static OrderPaymentService.ChannelCharge charge(String channel, int cents, String tradeNo) {
        return new OrderPaymentService.ChannelCharge(channel, cents, tradeNo);
    }

    private static int sum(List<OrderPaymentService.RefundLeg> legs) {
        return legs.stream().mapToInt(OrderPaymentService.RefundLeg::amountCents).sum();
    }

    private static int legOf(List<OrderPaymentService.RefundLeg> legs, String channel) {
        return legs.stream().filter(l -> channel.equalsIgnoreCase(l.channel()))
                .mapToInt(OrderPaymentService.RefundLeg::amountCents).sum();
    }

    @Test
    void pureChannelOrder_refundsThatChannelInFull() {
        List<OrderPaymentService.RefundLeg> legs = OrderPaymentService.planRefundLegs(
                List.of(charge(PayChannels.WECHAT, 100, "wx-t-1")), 100);

        assertEquals(1, legs.size());
        assertEquals(100, legs.get(0).amountCents());
        assertEquals("wx-t-1", legs.get(0).payTradeNo(), "腿必须带上原交易号，否则网关无从退起");
    }

    @Test
    void comboOrder_neverOverRefundsTheChannelLeg() {
        // MA-161 事故形态：钱包 60 + 微信 40，全额退 100。
        List<OrderPaymentService.RefundLeg> legs = OrderPaymentService.planRefundLegs(
                List.of(charge(PayChannels.WECHAT, 40, "wx-t-2"),
                        charge(PayChannels.BALANCE, 60, null)), 100);

        assertEquals(40, legOf(legs, PayChannels.WECHAT), "渠道腿只能退它实扣的 40");
        assertEquals(60, legOf(legs, PayChannels.BALANCE), "其余 60 回余额，不再静默少退用户");
        assertEquals(100, sum(legs), "腿合计必须等于待退额");
    }

    @Test
    void partialRefund_isTakenFromTheChannelFirst() {
        List<OrderPaymentService.RefundLeg> legs = OrderPaymentService.planRefundLegs(
                List.of(charge(PayChannels.WECHAT, 40, "wx-t-3"),
                        charge(PayChannels.BALANCE, 60, null)), 30);

        assertEquals(1, legs.size(), "部分退 30 全落在渠道腿上，余额腿不必出动");
        assertEquals(PayChannels.WECHAT, legs.get(0).channel());
        assertEquals(30, legs.get(0).amountCents());
    }

    @Test
    void channelChargeBiggerThanRefund_doesNotTouchBalance() {
        List<OrderPaymentService.RefundLeg> legs = OrderPaymentService.planRefundLegs(
                List.of(charge(PayChannels.ALIPAY, 90, "ali-t-4"),
                        charge(PayChannels.BALANCE, 10, null)), 50);

        assertEquals(50, legOf(legs, PayChannels.ALIPAY));
        assertEquals(0, legOf(legs, PayChannels.BALANCE), "余额腿只在渠道腿退完之后才兜其余");
    }

    @Test
    void pureBalanceOrder_refundsBalanceOnly() {
        List<OrderPaymentService.RefundLeg> legs = OrderPaymentService.planRefundLegs(
                List.of(charge(PayChannels.BALANCE, 50, null)), 50);

        assertEquals(1, legs.size());
        assertEquals(PayChannels.BALANCE, legs.get(0).channel());
        assertEquals(50, legs.get(0).amountCents());
    }

    @Test
    void refundExceedingCharges_failsLoudly() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> OrderPaymentService.planRefundLegs(
                        List.of(charge(PayChannels.WECHAT, 40, "wx-t-5"),
                                charge(PayChannels.BALANCE, 60, null)), 101));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(),
                "算不平只能失败，不能被改成「少退一点」——那是静默少退");
    }

    @Test
    void missingChannelLegForTheRemainder_failsLoudly() {
        // 只有一条微信 40 的流水却要退 100：合计判据先拦住（没有余额腿兜其余）
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> OrderPaymentService.planRefundLegs(
                        List.of(charge(PayChannels.WECHAT, 40, "wx-t-6")), 100));

        assertTrue(ex.getMessage().contains("实扣额合计"));
    }

    @Test
    void zeroAndNegativeRefund_produceNoLegs() {
        assertTrue(OrderPaymentService.planRefundLegs(List.of(charge(PayChannels.WECHAT, 10, "t")), 0).isEmpty());
        assertTrue(OrderPaymentService.planRefundLegs(List.of(charge(PayChannels.WECHAT, 10, "t")), -5).isEmpty());
    }

    @Test
    void legsAlwaysSumToTheRefundAmount() {
        List<OrderPaymentService.ChannelCharge> charges = List.of(
                charge(PayChannels.WECHAT, 5, "wx-a"),
                charge(PayChannels.ALIPAY, 90, "ali-b"),
                charge(PayChannels.BALANCE, 20, null));

        for (int refund : new int[]{1, 5, 6, 95, 100, 115}) {
            assertEquals(refund, sum(OrderPaymentService.planRefundLegs(charges, refund)),
                    "任意待退额下腿合计都必须相等（四层对账的第一层）");
        }
    }
}
