package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 低库存/缺货面只收在架 SKU（2026-10-30 拍板）：下架行清零前靠假账面隐身、清零后会被读成缺货，
 * 都不得进「需补货」视图。全量台账列表（lowStockOnly=false）刻意不滤下架——另一条拍板，别顺手对齐。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentListInventoryOnShelfTest {

    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock InventoryLotService inventoryLotService;
    @Mock SkuCatalogMapper skuCatalogMapper;

    private ReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new ReplenishmentService(
                inventoryRepository, null, null, null, null, inventoryLotService, null, null,
                new ObjectMapper(), null, null, null, null, null, null, null, null,
                skuCatalogMapper, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void listInventory_lowStockOnly_excludesDelistedRows() {
        when(inventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(row("EG-1", "100013"), row("EG-1", "100016")));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of());
        when(skuCatalogMapper.selectList(any()))
                .thenReturn(List.of(catalog("100013", "ACTIVE"), catalog("100016", "INACTIVE")));

        List<DeviceInventoryDto> rows = service.listInventory("EG-1", true);

        assertEquals(1, rows.size());
        assertEquals("100013", rows.get(0).skuId());
    }

    @Test
    void listInventory_fullList_keepsDelistedRows() {
        when(inventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(row("EG-1", "100013"), row("EG-1", "100016")));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of());

        List<DeviceInventoryDto> rows = service.listInventory("EG-1", false);

        assertEquals(2, rows.size());
    }

    private static DeviceSkuInventory row(String dev, String sku) {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setId(new DeviceSkuInventoryId(dev, sku));
        inv.setQuantity(0);
        inv.setCapacity(4);
        inv.setLowThreshold(2);
        return inv;
    }

    private static SkuCatalog catalog(String skuId, String status) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setStatus(status);
        return sku;
    }
}
