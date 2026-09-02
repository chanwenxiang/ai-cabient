package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WithdrawFeeCalculatorTest {

    @Test
    void compute_flatPlusBps() {
        // 10000 * 50bps = 50, + flat 30 = 80
        assertEquals(80L, WithdrawFeeCalculator.computeFeeCents(10_000L, 30L, 50L));
        assertEquals(0L, WithdrawFeeCalculator.computeFeeCents(10_000L, 0L, 0L));
    }

    @Test
    void compute_rejectsWhenFeeNotLessThanAmount() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> WithdrawFeeCalculator.computeFeeCents(100L, 100L, 0L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void netPayout_subtractsFee() {
        assertEquals(9900L, WithdrawFeeCalculator.netPayoutCents(10_000L, 100L));
        assertEquals(10_000L, WithdrawFeeCalculator.netPayoutCents(10_000L, null));
    }
}
