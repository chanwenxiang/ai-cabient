package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantInventoryPortalServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock InventoryLotService inventoryLotService;
    @Mock PullOffTaskMapper pullOffTaskRepository;
    @Mock DeviceSlotService deviceSlotService;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock ReplenishmentTaskLineMapper replenishmentTaskLineRepository;
    @Mock ReplenishmentRouteMapper replenishmentRouteRepository;
    @Mock DeviceInfoMapper deviceRepository;

    private MerchantInventoryPortalService service;

    @BeforeEach
    void setUp() {
        service = new MerchantInventoryPortalService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                inventoryRepository, inventoryLotService, pullOffTaskRepository, deviceSlotService,
                replenishmentTaskRepository, replenishmentTaskLineRepository, replenishmentRouteRepository,
                deviceRepository);
    }

    @Test
    void listInventory_whenFieldPackEmpty_returnsEmptyWithoutQuery() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of());

        List<DeviceInventoryDto> rows = service.listInventory(9L, null, false);

        assertTrue(rows.isEmpty());
        verify(inventoryRepository, never()).findAllLimit(2000);
        verify(inventoryRepository, never()).findLowStockLimit(500);
    }

    @Test
    void getReplenishmentTaskLines_whenMissing_notFound() {
        when(replenishmentTaskRepository.findById(404L)).thenReturn(java.util.Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getReplenishmentTaskLines(1L, 404L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void haversineCheckInDistanceM_samePointIsNearZero() {
        DeviceInfo device = new DeviceInfo();
        device.setLatitude(31.2304);
        device.setLongitude(121.4737);

        Double meters = MerchantInventoryPortalService.haversineCheckInDistanceM(
                device, 31.2304, 121.4737);

        assertTrue(meters != null && meters < 1.0);
    }

    @Test
    void haversineCheckInDistanceM_missingCoords_returnsNull() {
        assertNull(MerchantInventoryPortalService.haversineCheckInDistanceM(null, 1.0, 2.0));
        DeviceInfo device = new DeviceInfo();
        assertNull(MerchantInventoryPortalService.haversineCheckInDistanceM(device, 1.0, 2.0));
    }
}
