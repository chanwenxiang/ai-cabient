package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * B1-A0：纯渠道段的三态归类。FAILED 只对应「渠道判回落余额、未扣款」；
 * 任何异常都必须是 UNKNOWN（可能已扣，调用方保留 CHARGE_PENDING）。
 */
class OrderPaymentServiceChargeChannelOnceTest {

    @Test
    void chargedChannel_isSuccess() {
        OrderPaymentService.ChargeChannelAttempt attempt = OrderPaymentService.chargeChannelOnce(
                () -> new PayScoreService.ChargeResult(PayChannels.WECHAT, "WX-TRADE"));

        assertEquals(OrderPaymentService.ChargeChannelOutcome.SUCCESS, attempt.outcome());
        assertEquals("WX-TRADE", attempt.charge().tradeNo());
        assertNull(attempt.error());
    }

    @Test
    void balanceFallback_isFailedWithoutCharge() {
        OrderPaymentService.ChargeChannelAttempt attempt = OrderPaymentService.chargeChannelOnce(
                () -> new PayScoreService.ChargeResult(PayChannels.BALANCE, null));

        assertEquals(OrderPaymentService.ChargeChannelOutcome.FAILED, attempt.outcome());
        assertEquals(PayChannels.BALANCE, attempt.charge().channel());
        assertNull(attempt.error());
    }

    @Test
    void exception_isUnknownAndKeepsOriginalError() {
        RuntimeException timeout = new IllegalStateException("channel timeout");
        OrderPaymentService.ChargeChannelAttempt attempt = OrderPaymentService.chargeChannelOnce(
                () -> {
                    throw timeout;
                });

        assertEquals(OrderPaymentService.ChargeChannelOutcome.UNKNOWN, attempt.outcome());
        assertNull(attempt.charge());
        assertSame(timeout, attempt.error());
    }
}
