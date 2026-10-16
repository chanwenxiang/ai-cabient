package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * AU-038：结算行金额 {@code unitPrice * quantity} 与订单合计此前都是 int 运算——
 * 上报侧 quantity 只判 &gt;0 无上界，超大值与单价相乘溢出为负 ⇒ 负金额订单行 +
 * 负合计（{@code chargeOrder} 见负额直接跳过扣款标 PAID，等于「零元白拿」）。
 *
 * <p>修法口径：计算全程走 long，结果越出 [0, Integer.MAX_VALUE] 一律显式 400——
 * 仅靠数量上界挡不住「单价×数量」的乘积溢出，把结果本身夹进列域才是硬保证。</p>
 */
@ExtendWith(MockitoExtension.class)
class SettlementOrderLineOverflowTest {

    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DeviceSlotMapper slotRepository;
    @Mock private MerchantSkuPricingService skuPricingService;
    @Mock private MemberService memberService;
    @Mock private CouponService couponService;
    @Mock private RevenueSplitService revenueSplitService;
    @Mock private OrderPaymentService orderPaymentService;
    @Mock private SystemConfigService systemConfigService;

    private SettlementOrderSupport support;

    @BeforeEach
    void setUp() {
        support = new SettlementOrderSupport(
                skuCatalogRepository, orderRepository, orderLineRepository, slotRepository,
                skuPricingService, memberService, couponService, null,
                revenueSplitService, orderPaymentService, systemConfigService, new OrderViewAssembler());
    }

    private void givenPrice(int unitPriceCents) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-A");
        sku.setSkuName("测试商品-A");
        sku.setPurchaseCostCents(10);
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents(isNull(), any())).thenReturn(unitPriceCents);
        when(memberService.applyMemberPriceDiscount(any(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    private CabinetOrder order() {
        CabinetOrder order = new CabinetOrder();
        order.setDeviceId(null); // 不推断货道，避免依赖 slotRepository
        return order;
    }

    @Test
    void oversizedQuantityOnSingleLine_isRejectedInsteadOfOverflowing() {
        givenPrice(100);
        CabinetOrder order = order();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> support.applyItemsToOrder(order,
                        List.of(new RecognizedItem("SKU-A", Integer.MAX_VALUE, 0.99f))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void lineAmountsSummingPastIntMax_isRejected() {
        givenPrice(Integer.MAX_VALUE / 2 + 1);
        CabinetOrder order = order();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> support.applyItemsToOrder(order, List.of(
                        new RecognizedItem("SKU-A", 1, 0.99f),
                        new RecognizedItem("SKU-A", 1, 0.99f))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void ordinaryQuantities_stillSettleUnchanged() {
        givenPrice(500);
        CabinetOrder order = order();
        support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-A", 3, 0.99f)));

        assertEquals(1, order.getLines().size());
        assertEquals(1500, order.getLines().get(0).getLineAmountCents());
        assertEquals(1500, order.getTotalAmountCents());
    }
}
