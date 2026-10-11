package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.trade.domain.ShoppingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestockSnapshotServiceTest {

    @Mock
    private GravitySettlementHelper gravityHelper;
    @Mock
    private DeviceSlotService deviceSlotService;
    @Mock
    private DistributedLockService distributedLockService;

    private RestockSnapshotService restockSnapshotService;

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Integer>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L)))
                .thenReturn(true);
        restockSnapshotService = new RestockSnapshotService(
                gravityHelper, deviceSlotService, distributedLockService);
    }

    @Test
    void applySnapshot_slotGravity_updatesPhysicalCounts() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-RESTOCK-1");
        session.setDeviceId("CAB-001");
        session.setGravityDeltas("[{\"skuId\":\"SKU-1\",\"delta\":3,\"slotId\":\"A1\"}]");

        var delta = new GravityDeltaRequest.GravityDeltaItem("SKU-1", 3, "A1");
        when(gravityHelper.parse(any())).thenReturn(List.of(delta));
        when(gravityHelper.hasSlotSpecificDeltas(any())).thenReturn(true);
        when(deviceSlotService.loadBookQtyBySlot("CAB-001")).thenReturn(Map.of("A1", 5));
        when(deviceSlotService.applyPhysicalSnapshot(eq("CAB-001"), anyMap(), eq("GRAVITY_SLOT"), eq("S-RESTOCK-1")))
                .thenReturn(1);

        int updated = restockSnapshotService.applySnapshot(session);

        assertEquals(1, updated);
        ArgumentCaptor<Map<String, Integer>> physicalCaptor = mapCaptor();
        verify(deviceSlotService).applyPhysicalSnapshot(
                eq("CAB-001"), physicalCaptor.capture(), eq("GRAVITY_SLOT"), eq("S-RESTOCK-1"));
        assertEquals(8, physicalCaptor.getValue().get("A1"));
    }


    @Test
    void applySnapshot_skuGravityNegative_distributesAcrossSlotsOnce() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-RESTOCK-3");
        session.setDeviceId("CAB-001");
        session.setGravityDeltas("[{\"skuId\":\"SKU-1\",\"delta\":-1}]");

        var delta = new GravityDeltaRequest.GravityDeltaItem("SKU-1", -1, null);
        when(gravityHelper.parse(any())).thenReturn(List.of(delta));
        when(gravityHelper.hasSlotSpecificDeltas(any())).thenReturn(false);
        when(deviceSlotService.listEnabledSlotsForSku("CAB-001", "SKU-1")).thenReturn(List.of(
                new DeviceSlotService.SlotBookView("A1", 20),
                new DeviceSlotService.SlotBookView("A2", 20)));
        when(deviceSlotService.loadBookQtyBySlot("CAB-001")).thenReturn(Map.of("A1", 10, "A2", 4));
        when(deviceSlotService.applyPhysicalSnapshot(eq("CAB-001"), anyMap(), eq("GRAVITY_SKU"), eq("S-RESTOCK-3")))
                .thenReturn(2);

        int updated = restockSnapshotService.applySnapshot(session);

        assertEquals(2, updated);
        ArgumentCaptor<Map<String, Integer>> physicalCaptor = mapCaptor();
        verify(deviceSlotService).applyPhysicalSnapshot(
                eq("CAB-001"), physicalCaptor.capture(), eq("GRAVITY_SKU"), eq("S-RESTOCK-3"));
        Map<String, Integer> physical = physicalCaptor.getValue();
        assertEquals(9, physical.get("A1"));
        assertEquals(4, physical.get("A2"));
    }
}
