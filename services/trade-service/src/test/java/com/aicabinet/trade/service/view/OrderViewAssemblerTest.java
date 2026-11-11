package com.aicabinet.trade.service.view;

import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderViewAssemblerTest {

    private final OrderViewAssembler assembler = new OrderViewAssembler();

    @Test
    void normalizePayChannel_blPrefixBecomesBalance() {
        CabinetOrder order = new CabinetOrder();
        order.setPayChannel("WECHAT");
        order.setPaymentOperationId("BL-123");
        assertEquals("BALANCE", OrderViewAssembler.normalizePayChannel(order));
    }

    @Test
    void assemble_withPreloadedImages_usesPreloaded() {
        // R14-F2：列表页跨单批量取图——preloadedImages 非 null 时不走 loadSkuImages
        CabinetOrder order = new CabinetOrder();
        order.setPayChannel("WECHAT");
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId("SKU-1");
        line.setQuantity(1);
        line.setLineAmountCents(300);

        var preloaded = java.util.Map.of("SKU-1", "https://oss/sku1.jpg");
        OrderReadModel model = assembler.assemble(
                order, List.of(line), null, null, null, null, false, preloaded);
        assertEquals("https://oss/sku1.jpg", model.coverImageUrl());
    }

    @Test
    void assembleSummary_buildsLineSummaryAndOriginal() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O1");
        order.setSessionId("S1");
        order.setDeviceId("D1");
        order.setDeviceName("柜A");
        order.setMerchantName("商户甲");
        order.setTotalAmountCents(800);
        order.setCouponDiscountCents(100);
        order.setMemberDiscountCents(50);
        order.setStatus("PAID");
        order.setPayChannel("WECHAT");

        CabinetOrderLine a = new CabinetOrderLine();
        a.setSkuId("A");
        a.setSkuName("牛奶");
        a.setQuantity(1);
        a.setSlotId("1");
        CabinetOrderLine b = new CabinetOrderLine();
        b.setSkuId("B");
        b.setSkuName("面包");
        b.setQuantity(2);
        b.setBatchNo("B1");
        CabinetOrderLine c = new CabinetOrderLine();
        c.setSkuId("C");
        c.setSkuName("水");
        c.setQuantity(1);

        OrderReadModel model = assembler.assembleSummary(
                order, List.of(a, b, c), "SETTLED", null, null, null);

        assertEquals(950, model.originalAmountCents());
        assertEquals(4, model.lineCount());
        assertEquals("牛奶 x1 ·货道1、面包 x2 @B1 等3件", model.lineSummary());
        assertEquals("柜A", model.deviceName());
        assertNull(model.lines());
    }

    @Test
    void originalAmount_includesActivityDiscount() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-ACT");
        order.setTotalAmountCents(800);
        order.setMemberDiscountCents(50);
        order.setActivityDiscountCents(100);

        OrderReadModel model = assembler.assembleSummary(
                order, List.of(), "SETTLED", null, null, null);

        assertEquals(950, model.originalAmountCents(), "原价必须把活动优惠也加回");
    }
}
