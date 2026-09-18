package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.domain.InventoryMovement;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.InventoryMovementMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H18：按批次核销/下架时，批次在库量不足必须整体拒绝（409），不得静默少扣。 */
@ExtendWith(MockitoExtension.class)
class InventoryLotBatchDeductTest {

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

    private DeviceSkuLot lot(int quantity) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("L1");
        lot.setDeviceId("DEV-H18");
        lot.setSkuId("SKU-H18");
        lot.setBatchNo("B-H18");
        lot.setQuantity(quantity);
        lot.setStatus("ON_SALE");
        lot.setExpiryDate(LocalDate.now().plusDays(30));
        return lot;
    }

    @Test
    void writeOffLots_batchShort_failsWholeOperationWithConflict() {
        when(distributedLockService.tryLock(any(), eq(60L), eq(5L))).thenReturn(true);
        when(lotRepository.findAllByDeviceIdAndSkuIdAndBatchNo("DEV-H18", "SKU-H18", "B-H18"))
                .thenReturn(List.of(lot(3)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.writeOffLots("DEV-H18", "SKU-H18", "B-H18", 10, 1L, "REF-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // 不得留下部分扣减流水
        verify(movementRepository, never()).save(any(InventoryMovement.class));
    }

    @Test
    void writeOffLots_exactQuantity_succeeds() {
        when(distributedLockService.tryLock(any(), eq(60L), eq(5L))).thenReturn(true);
        DeviceSkuLot lot = lot(10);
        when(lotRepository.findAllByDeviceIdAndSkuIdAndBatchNo("DEV-H18", "SKU-H18", "B-H18"))
                .thenReturn(List.of(lot));

        service.writeOffLots("DEV-H18", "SKU-H18", "B-H18", 10, 1L, "REF-2");

        assertEquals(0, lot.getQuantity());
        verify(movementRepository).save(any(InventoryMovement.class));
    }

    @Test
    void pullOff_batchShort_failsWholeOperationWithConflict() {
        when(distributedLockService.tryLock(any(), eq(60L), eq(5L))).thenReturn(true);
        when(lotRepository.findAllByDeviceIdAndSkuIdAndBatchNo("DEV-H18", "SKU-H18", "B-H18"))
                .thenReturn(List.of(lot(1), lot(2)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pullOff("DEV-H18", "SKU-H18", "B-H18", 9, 1L, "REF-3"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(movementRepository, never()).save(any(InventoryMovement.class));
    }
}
