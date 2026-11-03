package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-147：全额退（争议免单 / 运营全额退款）必须返还已核销券并回补活动预算。
 * 原来这条链全文零券处理——返还只接在比例退路径上，于是「钱退了、券没回来」。
 *
 * <p>钉两件事：① 结清单源被调到；② 它发生在 save <b>之前</b>
 * （{@code settleAfterFullRefund} 会清 couponId/抵扣列，save 之后只改内存不再落库）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementWaiveCouponReturnTest {

    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private OrderPaymentService orderPaymentService;
    @Mock private InventoryService inventoryService;
    @Mock private RevenueSplitService revenueSplitService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private CouponService couponService;

    private SettlementWaiveRefundService service;

    @BeforeEach
    void setUp() {
        service = new SettlementWaiveRefundService(sessionRepository, orderRepository, null,
                orderPaymentService, inventoryService, revenueSplitService,
                distributedLockService, couponService, null);
    }

    @Test
    void finalizeFullRefund_settlesCouponBeforeSavingOrder() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-147");
        order.setStatus("PAID");
        order.setCouponId(55L);
        order.setCouponDiscountCents(200);
        order.setTotalAmountCents(500);
        when(orderRepository.findByIdForUpdate("O-147")).thenReturn(Optional.of(order));

        service.finalizeWaiveRefund(new SettlementWaiveRefundService.WaiveRefundPrep(
                order, 500, "争议免单退款(不回库)", false));

        InOrder ordered = inOrder(couponService, orderRepository);
        ordered.verify(couponService).settleAfterFullRefund(order);
        ordered.verify(orderRepository).save(order);
        verify(revenueSplitService).voidSplitOnFullRefund("O-147");
    }

    @Test
    void finalizeFullRefund_alreadyRefunded_isIdempotentAndDoesNotSettleTwice() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-147B");
        order.setStatus("REFUNDED");
        order.setCouponId(56L);
        when(orderRepository.findByIdForUpdate("O-147B")).thenReturn(Optional.of(order));

        int cents = service.finalizeWaiveRefund(
                new SettlementWaiveRefundService.WaiveRefundPrep(order, 300, "重试", false));

        org.junit.jupiter.api.Assertions.assertEquals(300, cents);
        verify(couponService, org.mockito.Mockito.never()).settleAfterFullRefund(org.mockito.ArgumentMatchers.any());
        verify(orderRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}
