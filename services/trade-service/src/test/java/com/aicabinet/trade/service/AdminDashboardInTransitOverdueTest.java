package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.trade.domain.WarehouseInTransit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminDashboardInTransitOverdueTest {

    @Test
    void aggregatesSameOutboundIntoOneActionItem() {
        Instant t0 = Instant.parse("2026-09-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-09-01T01:00:00Z");
        List<OpsActionItemDto> items = AdminDashboardService.aggregateInTransitOverdueActionItems(List.of(
                line(1L, 2L, "CAB-001", "SKU-A", 3, t1),
                line(2L, 2L, "CAB-001", "SKU-B", 5, t0),
                line(3L, 2L, "CAB-001", "SKU-A", 1, t1)
        ));

        assertEquals(1, items.size());
        OpsActionItemDto item = items.get(0);
        assertEquals("IN_TRANSIT_OVERDUE", item.type());
        assertEquals("HIGH", item.severity());
        assertEquals("补货签收超时", item.title());
        assertEquals("CAB-001", item.deviceId());
        assertEquals(2L, item.taskId());
        assertNull(item.skuId());
        assertEquals(t0, item.createdAt());
        assertTrue(item.detail().contains("出库单 2"));
        assertTrue(item.detail().contains("2 个 SKU"));
        assertTrue(item.detail().contains("共 9 件"));
    }

    @Test
    void keepsSeparateItemsForDifferentOutbounds() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        List<OpsActionItemDto> items = AdminDashboardService.aggregateInTransitOverdueActionItems(List.of(
                line(1L, 10L, "CAB-001", "SKU-A", 1, now),
                line(2L, 11L, "CAB-001", "SKU-B", 2, now)
        ));
        assertEquals(2, items.size());
        assertEquals(10L, items.get(0).taskId());
        assertEquals(11L, items.get(1).taskId());
    }

    @Test
    void keepsOrphanLinesWithoutOutboundId() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        WarehouseInTransit orphan = line(99L, null, "CAB-001", "SKU-X", 4, now);
        List<OpsActionItemDto> items = AdminDashboardService.aggregateInTransitOverdueActionItems(List.of(orphan));
        assertEquals(1, items.size());
        assertEquals("SKU-X", items.get(0).skuId());
        assertEquals(99L, items.get(0).taskId());
        assertTrue(items.get(0).detail().contains("SKU-X"));
    }

    private static WarehouseInTransit line(
            Long transitId, Long outboundId, String deviceId, String skuId, int qty, Instant createdAt) {
        WarehouseInTransit t = new WarehouseInTransit();
        t.setTransitId(transitId);
        t.setOutboundId(outboundId);
        t.setDeviceId(deviceId);
        t.setSkuId(skuId);
        t.setBatchNo("B-" + skuId);
        t.setQuantity(qty);
        t.setStatus("IN_TRANSIT");
        t.setCreatedAt(createdAt);
        return t;
    }
}
