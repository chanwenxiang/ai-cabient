package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.repository.PaymentOperationRepository;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentIdempotencyTest {

    @Mock UserInfoRepository userInfoRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock PayScoreService payScoreService;
    @Mock WeChatPayClient weChatPayClient;
    @Mock AlipayPayClient alipayPayClient;
    @Mock PaymentOperationRepository paymentOperationRepository;

    private OrderPaymentService service;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentService(
                userInfoRepository,
                userAccountRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(true),
                paymentOperationRepository,
                balanceLedgerService,
                new CheckoutProperties(true));
    }

    @Test
    void duplicateRefund_skipsSecondLedgerChange() {
        CabinetOrder order = order("O-REF-1", 10001L, 800, "BALANCE");
        PaymentOperation completed = new PaymentOperation();
        completed.setStatus("COMPLETED");
        when(paymentOperationRepository.findByIdempotencyKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(completed));

        service.refundOrder(order, 300, "dispute waive");
        service.refundOrder(order, 300, "dispute waive");

        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void duplicateCharge_skipsSecondLedgerChange() {
        CabinetOrder order = order("O-CHG-1", 10001L, 500, "BALANCE");
        PaymentOperation completed = new PaymentOperation();
        completed.setStatus("COMPLETED");
        completed.setChannel("BALANCE");
        when(paymentOperationRepository.findByIdempotencyKey("CHARGE:O-CHG-1:500"))
                .thenReturn(Optional.of(completed));

        service.chargeOrder(order);
        service.chargeOrder(order);

        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void firstRefund_writesLedgerOnce() {
        CabinetOrder order = order("O-REF-2", 10001L, 800, "BALANCE");
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        service.refundOrder(order, 200, "duplicate test");

        verify(balanceLedgerService, times(1)).change(
                eq(10001L), eq(200), eq("REFUND"), eq("O-REF-2"), any(), eq("duplicate test"));
    }

    private CabinetOrder order(String orderId, long userId, int amountCents, String channel) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setTotalAmountCents(amountCents);
        order.setPayChannel(channel);
        return order;
    }
}
