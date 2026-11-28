package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderPaymentRefundFingerprintTest {

    private PaymentOperationMapper paymentOperationMapper;
    private OrderPaymentService service;

    @BeforeEach
    void setUp() {
        paymentOperationMapper = mock(PaymentOperationMapper.class);
        service = mock(OrderPaymentService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "paymentOperationRepository", paymentOperationMapper);
    }

    @Test
    void completedRefundCents_sumsChannelAndBalanceLegsForLatestFingerprintRequest() {
        Instant now = Instant.now();
        PaymentOperation channel = op("REFUND:O-1:500:L:fp", 400, now);
        PaymentOperation balance = op("REFUND:O-1:500:L:fp:BALANCE", 100, now.plusSeconds(1));
        PaymentOperation olderRequest = op("REFUND:O-1:300:L:fp", 300, now.minusSeconds(10));
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(olderRequest, channel, balance));

        assertEquals(500, service.findCompletedRefundCentsByFingerprint("O-1", "fp"));
    }

    @Test
    void completedRefundCents_returnsZeroWhenNoMatchingCompletedRefund() {
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.findCompletedRefundCentsByFingerprint("O-1", "missing"));
    }

    private static PaymentOperation op(String idemKey, int amount, Instant createdAt) {
        PaymentOperation op = new PaymentOperation();
        op.setOrderId("O-1");
        op.setOperationType("REFUND");
        op.setStatus("COMPLETED");
        op.setIdempotencyKey(idemKey);
        op.setAmountCents(amount);
        op.setCreatedAt(createdAt);
        return op;
    }
}
