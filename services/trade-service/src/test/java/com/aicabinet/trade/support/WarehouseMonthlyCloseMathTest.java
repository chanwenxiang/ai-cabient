package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.WarehouseMovement;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WarehouseMonthlyCloseMathTest {

    private static final Instant START = LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant END = LocalDate.of(2026, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    @Test
    void expectedEqualsOpeningPlusInMinusOut() {
        WarehouseMovement open = move("SKU-A", "PURCHASE_RECEIVE", null, 10, START.minusSeconds(3600));
        WarehouseMovement buy = move("SKU-A", "PURCHASE_RECEIVE", null, 4, START.plusSeconds(10));
        WarehouseMovement ship = move("SKU-A", "OUTBOUND_SHIP", "WAREHOUSE_OUTBOUND", -3, START.plusSeconds(20));
        WarehouseMovement loss = move("SKU-A", "STOCKTAKE", "STOCKTAKE", -1, START.plusSeconds(30));

        Map<String, WarehouseMonthlyCloseMath.Acc> acc = WarehouseMonthlyCloseMath.aggregate(
                List.of(open, buy, ship, loss), START, END);
        WarehouseMonthlyCloseMath.Acc a = acc.get("SKU-A");
        assertEquals(10, a.openingQty);
        assertEquals(4, a.purchaseInQty);
        assertEquals(3, a.restockQty);
        assertEquals(1, a.lossQty);
        assertEquals(11, a.expectedQty());
    }

    @Test
    void transferAndReturnClassified() {
        WarehouseMovement in = move("SKU-B", "BIN_STOCK", "TRANSFER_IN", 5, START.plusSeconds(1));
        WarehouseMovement out = move("SKU-B", "BIN_STOCK", "TRANSFER_OUT", -2, START.plusSeconds(2));
        WarehouseMovement ret = move("SKU-B", "PURCHASE_RETURN", "PURCHASE_RETURN", -1, START.plusSeconds(3));
        WarehouseMonthlyCloseMath.Acc a = WarehouseMonthlyCloseMath.aggregate(
                List.of(in, out, ret), START, END).get("SKU-B");
        assertEquals(5, a.transferInQty);
        assertEquals(2, a.transferOutQty);
        assertEquals(1, a.returnQty);
        assertEquals(2, a.expectedQty());
    }

    @Test
    void afterMonthIgnored() {
        WarehouseMovement late = move("SKU-C", "PURCHASE_RECEIVE", null, 9, END);
        assertNull(WarehouseMonthlyCloseMath.aggregate(List.of(late), START, END).get("SKU-C"));
    }

    @Test
    void writeOffSplitFromTransferOut() {
        // V348：仓库报损核销（BIN_STOCK+WRITE_OFF）单独列，不再混入 transferOut
        WarehouseMovement wo = move("SKU-D", "BIN_STOCK", "WRITE_OFF", -3, START.plusSeconds(1));
        WarehouseMovement transfer = move("SKU-D", "BIN_STOCK", "TRANSFER_OUT", -2, START.plusSeconds(2));
        WarehouseMovement open = move("SKU-D", "PURCHASE_RECEIVE", null, 10, START.minusSeconds(3600));
        WarehouseMonthlyCloseMath.Acc a = WarehouseMonthlyCloseMath.aggregate(
                List.of(open, wo, transfer), START, END).get("SKU-D");
        assertEquals(3, a.writeOffQty);
        assertEquals(2, a.transferOutQty, "核销不再计入外调列（18 列口径）");
        assertEquals(8, a.expectedQty(), "expected=核销前应有：期初10−调出2（核销3走损耗列展示不进公式）");
    }

    @Test
    void guestWaterSplitFromRestock() {
        // V348：客情出库（OUTBOUND_SHIP+GUEST_WATER）单独列，不混入机器补货（restockQty）
        WarehouseMovement guest = move("SKU-E", "OUTBOUND_SHIP", "GUEST_WATER", -5, START.plusSeconds(1));
        WarehouseMovement normal = move("SKU-E", "OUTBOUND_SHIP", "WAREHOUSE_OUTBOUND", -4, START.plusSeconds(2));
        WarehouseMovement cancel = move("SKU-E", "OUTBOUND_CANCEL", "GUEST_WATER", 1, START.plusSeconds(3));
        WarehouseMonthlyCloseMath.Acc a = WarehouseMonthlyCloseMath.aggregate(
                List.of(guest, normal, cancel), START, END).get("SKU-E");
        assertEquals(4, a.guestWaterQty, "客情出库 5 − 取消 1");
        assertEquals(4, a.restockQty);
    }

    private static WarehouseMovement move(String sku, String type, String ref, int delta, Instant at) {
        WarehouseMovement row = new WarehouseMovement();
        row.setSkuId(sku);
        row.setMovementType(type);
        row.setRefType(ref);
        row.setDeltaQty(delta);
        row.setCreatedAt(at);
        return row;
    }
}
