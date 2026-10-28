package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.InventoryMovementMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 无批次支撑的裸库存不得被批次汇总吞掉。
 *
 * <p>真实事故（2026-10-28 真机）：100013 账面 2 件、从无批次行；补货上架 2 件写批次后，
 * {@code syncAggregateInventory} 用批次汇总覆盖账面，结果仍是 2（应为 4）。
 */
@ExtendWith(MockitoExtension.class)
class InventoryLotUntrackedMaterializeTest {

    private static final String DEVICE = "EG943322621391";
    private static final String SKU = "100013";

    @Mock private DeviceSkuLotMapper lotRepository;
    @Mock private InventoryMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PullOffTaskMapper pullOffTaskRepository;
    @Mock private DeviceSlotMapper slotRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private InventoryLotService self;

    private InventoryLotService service;

    @BeforeEach
    void setUp() {
        service = new InventoryLotService(lotRepository, movementRepository, inventoryRepository,
                skuCatalogRepository, pullOffTaskRepository, slotRepository,
                distributedLockService, self);
    }

    private DeviceSkuInventory inventory(int quantity) {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setId(new DeviceSkuInventoryId(DEVICE, SKU));
        inv.setQuantity(quantity);
        inv.setCapacity(4);
        inv.setLowThreshold(2);
        return inv;
    }

    private DeviceSkuLot existingLot(int quantity) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("L-EXIST");
        lot.setDeviceId(DEVICE);
        lot.setSkuId(SKU);
        lot.setBatchNo("B-EXIST");
        lot.setQuantity(quantity);
        lot.setStatus("ON_SALE");
        lot.setExpiryDate(LocalDate.now().plusDays(30));
        return lot;
    }

    @Test
    void bareInventoryWithoutAnyLot_isMaterializedBeforeAggregateOverwrite() {
        when(lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(DEVICE, SKU)).thenReturn(List.of());
        when(inventoryRepository.findById(new DeviceSkuInventoryId(DEVICE, SKU)))
                .thenReturn(Optional.of(inventory(2)));
        when(lotRepository.sumSellableQuantity(DEVICE, SKU)).thenReturn(2);

        service.syncAggregateInventory(DEVICE, SKU);

        ArgumentCaptor<DeviceSkuLot> lotCaptor = ArgumentCaptor.forClass(DeviceSkuLot.class);
        verify(lotRepository).save(lotCaptor.capture());
        DeviceSkuLot materialized = lotCaptor.getValue();
        assertEquals("LEGACY-OPENING", materialized.getBatchNo());
        assertEquals(2, materialized.getQuantity());
        assertEquals(DEVICE, materialized.getDeviceId());
        assertEquals(SKU, materialized.getSkuId());
        assertNotNull(materialized.getLotId());

        // 必须是「先补批次、再求和覆盖」，否则汇总里仍然没有这 2 件
        InOrder order = inOrder(lotRepository);
        order.verify(lotRepository).save(any(DeviceSkuLot.class));
        order.verify(lotRepository).sumSellableQuantity(DEVICE, SKU);

        ArgumentCaptor<DeviceSkuInventory> invCaptor = ArgumentCaptor.forClass(DeviceSkuInventory.class);
        verify(inventoryRepository).save(invCaptor.capture());
        assertEquals(2, invCaptor.getValue().getQuantity());
    }

    @Test
    void alreadyTrackedSku_isNotMaterializedAgain() {
        when(lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(DEVICE, SKU))
                .thenReturn(List.of(existingLot(3)));
        when(inventoryRepository.findById(new DeviceSkuInventoryId(DEVICE, SKU)))
                .thenReturn(Optional.of(inventory(3)));
        when(lotRepository.sumSellableQuantity(DEVICE, SKU)).thenReturn(3);

        service.syncAggregateInventory(DEVICE, SKU);

        verify(lotRepository, never()).save(any(DeviceSkuLot.class));
    }

    @Test
    void zeroInventory_isNotMaterialized() {
        when(lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(DEVICE, SKU)).thenReturn(List.of());
        when(inventoryRepository.findById(new DeviceSkuInventoryId(DEVICE, SKU)))
                .thenReturn(Optional.of(inventory(0)));
        when(lotRepository.sumSellableQuantity(DEVICE, SKU)).thenReturn(0);

        service.syncAggregateInventory(DEVICE, SKU);

        verify(lotRepository, never()).save(any(DeviceSkuLot.class));
    }

    @Test
    void missingInventoryRow_isNotMaterialized() {
        when(lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(DEVICE, SKU)).thenReturn(List.of());
        when(inventoryRepository.findById(new DeviceSkuInventoryId(DEVICE, SKU))).thenReturn(Optional.empty());
        when(lotRepository.sumSellableQuantity(DEVICE, SKU)).thenReturn(0);

        service.syncAggregateInventory(DEVICE, SKU);

        verify(lotRepository, never()).save(any(DeviceSkuLot.class));
        verify(inventoryRepository).save(any(DeviceSkuInventory.class));
        verify(movementRepository, never()).save(any());
        verify(slotRepository, never()).findByIdDeviceIdOrderByRowNoAscColNoAsc(anyString());
    }
}
