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
}
