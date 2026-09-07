package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpdateMerchantDeviceSettingsRequest;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantDevicePortalServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock DeviceSlotService deviceSlotService;
    @Mock AdminAuditService auditService;
    @Mock DeviceTemperatureReadingMapper temperatureReadingRepository;
    @Mock DeviceServiceClient deviceServiceClient;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock MerchantMapper merchantRepository;
    @Mock DistributedLockService distributedLockService;
    @Mock MerchantSelfServiceGate merchantSelfServiceGate;

    private MerchantDevicePortalService service;

    @BeforeEach
    void setUp() {
        service = new MerchantDevicePortalService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                deviceRepository, deviceSlotService, auditService, temperatureReadingRepository,
                deviceServiceClient, sessionRepository, orderRepository, replenishmentTaskRepository,
                merchantRepository, distributedLockService, merchantSelfServiceGate);
    }

    @Test
    void isTempOutOfRange_whenDiffAbove3() {
        DeviceInfo d = new DeviceInfo();
        d.setTargetTempC(5);
        d.setCurrentTempC(9);
        assertTrue(MerchantDevicePortalService.isTempOutOfRange(d));
        d.setCurrentTempC(7);
        assertFalse(MerchantDevicePortalService.isTempOutOfRange(d));
    }

    @Test
    void updateDeviceSettings_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                DeviceAssetService.deviceAssetLockKey("CAB-MP"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateDeviceSettings(1L, "CAB-MP",
                        new UpdateMerchantDeviceSettingsRequest("name", null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updateDeviceSettings_rejectsTargetTempOutOfRange() {
        when(distributedLockService.tryLock(
                DeviceAssetService.deviceAssetLockKey("CAB-MP"), 60L, 5L))
                .thenReturn(true);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-MP");
        device.setDeviceName("柜机");
        when(deviceRepository.findByIdForUpdate("CAB-MP")).thenReturn(java.util.Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateDeviceSettings(1L, "CAB-MP",
                        new UpdateMerchantDeviceSettingsRequest(null, null, null, 99, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("-30"));
    }
}
