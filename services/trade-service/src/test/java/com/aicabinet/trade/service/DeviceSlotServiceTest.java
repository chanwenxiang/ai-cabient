package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.aicabinet.trade.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
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
    private DeviceInfoMapper deviceRepository;
    @Mock
    private SkuCatalogMapper skuCatalogRepository;
    @Mock
    private SalesVelocityService salesVelocityService;
    @Mock
    private DistributedLockService distributedLockService;

    private DeviceSlotService deviceSlotService;

    @BeforeEach
    void setUp() {
        deviceSlotService = new DeviceSlotService(
                slotRepository, lotRepository, deviceRepository, null, skuCatalogRepository, null, null, null,
                salesVelocityService, null, null, null, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(deviceSlotService, "self", deviceSlotService);
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

    /** BUG-015：清库后模板 SKU 不存在时，仍应建空货道，不可因 FK 导致创建设备事务回滚。 */
    @Test
    void ensureDefaultSlots_missingCatalogSku_createsSlotsWithoutSku() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType(PlanogramTemplateService.DEFAULT_DEVICE_TYPE);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceRepository.findById(DEVICE_ID)).thenReturn(Optional.of(device));
        when(slotRepository.countByIdDeviceIdAndEnabledTrue(DEVICE_ID)).thenReturn(0L);
        when(slotRepository.existsById(any())).thenReturn(false);
        when(skuCatalogRepository.findById(anyString())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> deviceSlotService.ensureDefaultSlots(DEVICE_ID));

        ArgumentCaptor<DeviceSlot> captor = ArgumentCaptor.forClass(DeviceSlot.class);
        verify(slotRepository, atLeastOnce()).save(captor.capture());
        List<DeviceSlot> saved = captor.getAllValues();
        assertFalse(saved.isEmpty());
        assertEquals(PlanogramTemplateService.standardTemplate().size(), saved.size());
        Set<String> assigned = saved.stream()
                .map(DeviceSlot::getAssignedSkuId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        assertTrue(assigned.isEmpty(), "missing catalog SKUs must not be written to device_slot");
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
