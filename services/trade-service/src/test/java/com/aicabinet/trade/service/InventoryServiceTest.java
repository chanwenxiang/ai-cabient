package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 库存扣减加锁与不足校验。 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock InventoryLotService inventoryLotService;
    @Mock DeviceSlotService deviceSlotService;
    @Mock GravitySettlementHelper gravityHelper;
    @Mock DeviceValidationService deviceValidationService;
    @Mock DistributedLockService lockService;

    InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                inventoryRepository, inventoryLotService, deviceSlotService,
                gravityHelper, deviceValidationService, lockService);
        lenient().when(lockService.tryLock(eq("inv:CAB-001"), anyLong(), anyLong())).thenReturn(true);
        lenient().when(gravityHelper.parse(any())).thenReturn(List.of());
        lenient().when(gravityHelper.hasSlotSpecificDeltas(any())).thenReturn(false);
        lenient().when(inventoryLotService.hasSellableLots(any(), any())).thenReturn(false);
        lenient().when(inventoryLotService.deviceUsesLotLedger(any())).thenReturn(false);
    }

    @Test
    void deduct_usesDistributedLock() {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setId(new DeviceSkuInventoryId("CAB-001", "SKU-DEMO-001"));
        inv.setQuantity(5);
        when(inventoryRepository.findById(any())).thenReturn(Optional.of(inv));
        when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        inventoryService.deductForOrder("CAB-001",
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 2, 1f)), "S1");

        verify(lockService).tryLock("inv:CAB-001", 30, 5);
        verify(lockService).unlock("inv:CAB-001");
        assertEquals(3, inv.getQuantity());
    }

    @Test
    void deduct_busyLock_throwsConflict() {
        when(lockService.tryLock(eq("inv:CAB-001"), anyLong(), anyLong())).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> inventoryService.deductForOrder("CAB-001",
                        List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 1f))));
        assertTrue(ex.getReason().contains("库存繁忙"));
        verify(lockService, never()).unlock(any());
    }

    @Test
    void deduct_insufficient_throws() {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setId(new DeviceSkuInventoryId("CAB-001", "SKU-DEMO-001"));
        inv.setQuantity(1);
        when(inventoryRepository.findById(any())).thenReturn(Optional.of(inv));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> inventoryService.deductForOrder("CAB-001",
                        List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 3, 1f)), "S1"));
        assertTrue(ex.getReason().contains("库存不足"));
        verify(lockService).unlock("inv:CAB-001");
    }

    @Test
    void adjust_reduceQty_returnsOriginalBatches() {
        when(inventoryLotService.restoreToBatch(
                eq("CAB-001"), eq("SKU-DEMO-001"), eq("BATCH-A"), eq(1), eq("ORDER"), isNull()))
                .thenReturn("A1");

        Map<String, String> batches = inventoryService.adjustForOrder(
                "CAB-001",
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 2, 1f)),
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 1f)),
                Map.of("SKU-DEMO-001", "BATCH-A"));

        assertEquals("BATCH-A", batches.get("SKU-DEMO-001"));
        verify(inventoryLotService).restoreToBatch(
                "CAB-001", "SKU-DEMO-001", "BATCH-A", 1, "ORDER", null);
        verify(deviceSlotService).applyPhysicalAfterRestore(eq("CAB-001"), anyMap(), eq("REFUND"));
    }

    @Test
    void adjust_unchangedQty_stillReturnsOriginalBatches() {
        Map<String, String> batches = inventoryService.adjustForOrder(
                "CAB-001",
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 1f)),
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 1f)),
                Map.of("SKU-DEMO-001", "BATCH-KEEP"));

        assertEquals(Map.of("SKU-DEMO-001", "BATCH-KEEP"), batches);
        verify(inventoryLotService, never()).restoreToBatch(any(), any(), any(), anyInt(), any(), any());
        verify(inventoryLotService, never()).deductFefo(any(), any(), anyInt(), any(), any(), any());
    }
}
