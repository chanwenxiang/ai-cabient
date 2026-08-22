package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.aicabinet.trade.mapper.*;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceSlotServiceTest {

    private static final String DEVICE_ID = "CAB-001";

    @Mock
    private DeviceSlotMapper slotRepository;
    @Mock
    private DeviceSkuLotMapper lotRepository;
    @Mock
    private SalesVelocityService salesVelocityService;

    private DeviceSlotService deviceSlotService;

    @BeforeEach
    void setUp() {
        deviceSlotService = new DeviceSlotService(
                slotRepository, lotRepository, null, null, null, null, null, null,
                salesVelocityService, null, null, null);
    }

    @Test
    void validateRestockLine_withinCapacity_passes() {
        DeviceSlot slot = slot("A1", "SKU-DEMO-001", 8);
        when(slotRepository.findById(new DeviceSlotId(DEVICE_ID, "A1"))).thenReturn(Optional.of(slot));
        when(lotRepository.sumBookQtyBySlot(DEVICE_ID))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"A1", 6}));

        assertDoesNotThrow(() ->
                deviceSlotService.validateRestockLine(DEVICE_ID, "A1", "SKU-DEMO-001", 2));
    }

    @Test
    void validateRestockLine_exceedsMaxLevel_rejects() {
        DeviceSlot slot = slot("A1", "SKU-DEMO-001", 8);
        when(slotRepository.findById(new DeviceSlotId(DEVICE_ID, "A1"))).thenReturn(Optional.of(slot));
        when(lotRepository.sumBookQtyBySlot(DEVICE_ID))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"A1", 6}));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceSlotService.validateRestockLine(DEVICE_ID, "A1", "SKU-DEMO-001", 3));
        assertTrue(ex.getReason().contains("容量不足"));
    }

    @Test
    void validateRestockLine_skuMismatch_rejects() {
        DeviceSlot slot = slot("A1", "SKU-DEMO-001", 8);
        when(slotRepository.findById(new DeviceSlotId(DEVICE_ID, "A1"))).thenReturn(Optional.of(slot));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                deviceSlotService.validateRestockLine(DEVICE_ID, "A1", "SKU-OTHER", 1));
        assertTrue(ex.getReason().contains("已绑定"));
    }

    @Test
    void allocateRestockQuantity_splitsAcrossLowestBookSlots() {
        DeviceSlot a1 = slot("A1", "SKU-DEMO-001", 8);
        DeviceSlot a2 = slot("A2", "SKU-DEMO-001", 8);
        when(slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(DEVICE_ID))
                .thenReturn(List.of(a1, a2));
        when(lotRepository.sumBookQtyBySlot(DEVICE_ID)).thenReturn(List.of(
                new Object[]{"A1", 6},
                new Object[]{"A2", 2}
        ));

        List<DeviceSlotService.SlotRestockAllocation> result =
                deviceSlotService.allocateRestockQuantity(DEVICE_ID, "SKU-DEMO-001", 8);

        assertEquals(2, result.size());
        assertEquals("A2", result.get(0).slotCode());
        assertEquals(6, result.get(0).quantity());
        assertEquals("A1", result.get(1).slotCode());
        assertEquals(2, result.get(1).quantity());
    }

    @Test
    void allocateRestockQuantity_doesNotOverflowSlotCapacity() {
        DeviceSlot a1 = slot("A1", "SKU-DEMO-001", 8);
        when(slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(DEVICE_ID))
                .thenReturn(List.of(a1));
        when(lotRepository.sumBookQtyBySlot(DEVICE_ID))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"A1", 6}));

        List<DeviceSlotService.SlotRestockAllocation> result =
                deviceSlotService.allocateRestockQuantity(DEVICE_ID, "SKU-DEMO-001", 10);

        assertEquals(1, result.size());
        assertEquals("A1", result.get(0).slotCode());
        assertEquals(2, result.get(0).quantity());
    }

    @Test
    void applyPhysicalAfterSkuSale_decrementsSingleAssignedSlot() {
        DeviceSlot a1 = slot("A1", "SKU-DEMO-001", 20);
        a1.setLastPhysicalQty(14);
        when(slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(DEVICE_ID)).thenReturn(List.of(a1));
        when(lotRepository.sumBookQtyBySlot(DEVICE_ID))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"A1", 13}));
        when(slotRepository.findById(new DeviceSlotId(DEVICE_ID, "A1"))).thenReturn(Optional.of(a1));

        deviceSlotService.applyPhysicalAfterSkuSale(DEVICE_ID, Map.of("SKU-DEMO-001", 1), "S-1");

        assertEquals(13, a1.getLastPhysicalQty());
        verify(slotRepository).save(a1);
    }

    @Test
    void applyPhysicalAfterRestore_incrementsPhysical() {
        DeviceSlot a1 = slot("A1", "SKU-DEMO-001", 20);
        a1.setLastPhysicalQty(11);
        when(slotRepository.findById(new DeviceSlotId(DEVICE_ID, "A1"))).thenReturn(Optional.of(a1));

        deviceSlotService.applyPhysicalAfterRestore(DEVICE_ID, Map.of("A1", 1), "REFUND");

        assertEquals(12, a1.getLastPhysicalQty());
        verify(slotRepository).save(a1);
    }

    private static DeviceSlot slot(String slotCode, String skuId, int maxLevel) {
        DeviceSlot slot = new DeviceSlot();
        slot.setId(new DeviceSlotId(DEVICE_ID, slotCode));
        slot.setAssignedSkuId(skuId);
        slot.setMaxLevel(maxLevel);
        slot.setParLevel(maxLevel);
        slot.setEnabled(true);
        return slot;
    }
}
