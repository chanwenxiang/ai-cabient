package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryLotConcurrencyTest {

    @Mock private DeviceSkuLotMapper lotRepository;
    @Mock private InventoryMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PullOffTaskMapper pullOffTaskRepository;
    @Mock private DeviceSlotMapper slotRepository;
    @Mock private DistributedLockService distributedLockService;

    private InventoryLotService service;

    @BeforeEach
    void setUp() {
        service = new InventoryLotService(lotRepository, movementRepository, inventoryRepository,
                skuCatalogRepository, pullOffTaskRepository, slotRepository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void deductFefo_whenDeviceLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InventoryService.deviceLockKey("DEV-LOT-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deductFefo("DEV-LOT-1", "SKU-1", 1, "ORDER", "O-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deductFefo_bareAggregateWithNoLot_materializesOpeningBatch() {
        // F4：账面有 2 件、该 SKU 一条批次都没有 ⇒ 先并成 LEGACY-OPENING 再 FEFO，
        // 不能因为「同柜别的 SKU 有批次」就对这台柜的所有 SKU 一律 409。
        DeviceSkuInventory bare = new DeviceSkuInventory();
        bare.setId(new DeviceSkuInventoryId("DEV-LOT-3", "SKU-3"));
        bare.setQuantity(2);
        DeviceSkuLot opening = new DeviceSkuLot();
        opening.setLotId("L-OPENING");
        opening.setDeviceId("DEV-LOT-3");
        opening.setSkuId("SKU-3");
        opening.setBatchNo("LEGACY-OPENING");
        opening.setQuantity(2);
        opening.setStatus("ON_SALE");
        opening.setExpiryDate(LocalDate.now().plusDays(365));

        when(distributedLockService.tryLock(
                InventoryService.deviceLockKey("DEV-LOT-3"), 60L, 5L)).thenReturn(true);
        when(lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc("DEV-LOT-3", "SKU-3"))
                .thenReturn(List.of(), List.of(opening));
        when(inventoryRepository.findById(new DeviceSkuInventoryId("DEV-LOT-3", "SKU-3")))
                .thenReturn(Optional.of(bare));
        when(slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc("DEV-LOT-3"))
                .thenReturn(List.of());
        when(skuCatalogRepository.findById("SKU-3")).thenReturn(Optional.empty());
        when(lotRepository.deductQuantityAtomic(anyString(), anyInt())).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "self", mock(InventoryLotService.class));

        assertEquals("LEGACY-OPENING",
                service.deductFefo("DEV-LOT-3", "SKU-3", 2, "ORDER", "O-3").primaryBatch());
        verify(lotRepository).save(argThat(lot ->
                "LEGACY-OPENING".equals(lot.getBatchNo()) && lot.getQuantity() == 2));
    }

    @Test
    void pullOff_whenDeviceLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InventoryService.deviceLockKey("DEV-LOT-2"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pullOff("DEV-LOT-2", "SKU-2", "B-1", 1, 1L, "R-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
