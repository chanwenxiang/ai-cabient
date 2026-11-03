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

import java.util.List;

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
    @Mock JiangyiGatherService jiangyiGatherService;
    @Mock InventoryLotService inventoryLotService;
    @Mock MerchantOpsPolicyService merchantOpsPolicyService;
    @Mock DeviceValidationService deviceValidationService;
    @Mock JiangyiDeviceDirectory jiangyiDeviceDirectory;
    @Mock com.aicabinet.trade.mapper.DeviceOpsEventMapper opsEventRepository;
    @Mock com.aicabinet.trade.mapper.DeviceSkuInventoryMapper inventoryRepository;
    @Mock SessionService sessionService;
    @Mock MerchantDevicePortalService self;

    private MerchantDevicePortalService service;

    @BeforeEach
    void setUp() {
        service = new MerchantDevicePortalService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                deviceRepository, deviceSlotService, auditService, temperatureReadingRepository,
                deviceServiceClient, sessionRepository, orderRepository, replenishmentTaskRepository,
                merchantRepository, distributedLockService, merchantSelfServiceGate,
                jiangyiGatherService, inventoryLotService, merchantOpsPolicyService,
                deviceValidationService, jiangyiDeviceDirectory, opsEventRepository,
                inventoryRepository, sessionService, self,
                new DisplaySnapshotHelper(
                        org.mockito.Mockito.mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class),
                        org.mockito.Mockito.mock(com.aicabinet.trade.mapper.MerchantMapper.class)));
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
    void getTemperatureHistory_clampsHoursAndPassesPointLimit() {
        when(temperatureReadingRepository.findByDeviceIdSince(
                org.mockito.ArgumentMatchers.eq("CAB-T"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(5000)))
                .thenReturn(List.of());

        var rows = service.getTemperatureHistory(1L, "CAB-T", 999);

        assertTrue(rows.isEmpty());
        org.mockito.Mockito.verify(temperatureReadingRepository).findByDeviceIdSince(
                org.mockito.ArgumentMatchers.eq("CAB-T"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(5000));
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

    // ---------- 将邑采集模式（CB-023 商户端补充） ----------

    @Test
    void jiangyiGatherStatus_passesOwnershipChecksAndDelegates() {
        when(jiangyiGatherService.merchantStatus("CAB-JY"))
                .thenReturn(new com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantGatherStatusDto(true, true));

        var status = service.getJiangyiGatherStatus(1L, "CAB-JY");

        assertTrue(status.bound());
        assertTrue(status.gatherLocked());
        org.mockito.Mockito.verify(permissionService).requirePermission(1L, "merchant:devices:detail");
        org.mockito.Mockito.verify(merchantPortalGuard).requireAccess(1L);
        org.mockito.Mockito.verify(merchantFeaturePackService).requireDevicePack(
                1L, "CAB-JY", MerchantFeaturePacks.FIELD);
    }

    @Test
    void jiangyiGatherStart_requiresEditPermissionBeforeDelegation() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(permissionService).requirePermission(1L, "merchant:devices:edit");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.startJiangyiGather(1L, "CAB-JY", "1"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        org.mockito.Mockito.verify(jiangyiGatherService, org.mockito.Mockito.never())
                .startGather(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void jiangyiGatherStart_delegatesWithDoorPosition() {
        service.startJiangyiGather(1L, "CAB-JY", "1");

        org.mockito.Mockito.verify(jiangyiGatherService).startGather(1L, "CAB-JY", "1");
    }

    @Test
    void jiangyiGatherExit_delegatesAfterOwnershipChecks() {
        service.exitJiangyiGather(1L, "CAB-JY");

        org.mockito.Mockito.verify(jiangyiGatherService).exitGatherMode(1L, "CAB-JY");
        org.mockito.Mockito.verify(merchantFeaturePackService).requireDevicePack(
                1L, "CAB-JY", MerchantFeaturePacks.FIELD);
    }

    @Test
    void listDeviceSlots_doorCabinet_returnsEmptyWithoutQueryingSlots() {
        // 开门柜无货道概念（判据=将邑绑定）：商户面读空，残留货道行不外泄
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);

        List<com.aicabinet.common.dto.DeviceSlotDto> slots = service.listDeviceSlots(1L, "EG-DOOR");

        assertTrue(slots.isEmpty());
        org.mockito.Mockito.verify(deviceSlotService, org.mockito.Mockito.never())
                .listSlots(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    // ---------- 盘点开门 / 开门柜盘点（2026-10-28） ----------

    @Test
    void openStocktakeDoor_nonJiangyi_rejectsWithConflict() {
        when(jiangyiDeviceDirectory.isJiangyi("CAB-SPRING")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.openStocktakeDoor(1L, "CAB-SPRING"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        org.mockito.Mockito.verify(sessionRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void openStocktakeDoor_happyPath_persistsStocktakeSessionAndSendsStockCommand() {
        when(jiangyiDeviceDirectory.isJiangyi("EG-DOOR")).thenReturn(true);
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(sessionService.getSession(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        service.openStocktakeDoor(1L, "EG-DOOR");

        // 会话落库经 self 代理（事务边界）；mock 环境只验委托与指令路由
        org.mockito.Mockito.verify(self).persistStocktakeOpeningSession(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("EG-DOOR"),
                org.mockito.ArgumentMatchers.anyString());
        // 盘点开门走补货开门真路由（sendOpenStockDoor），非运维开门（将邑不支持）
        org.mockito.Mockito.verify(deviceServiceClient).requestOpenDoorStock(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("EG-DOOR"),
                org.mockito.ArgumentMatchers.eq(1L));
        org.mockito.Mockito.verify(deviceValidationService).ensureOpsRemoteDoorAllowed("EG-DOOR");
    }

    @Test
    void openStocktakeDoor_commandFailure_marksSessionFailed() {
        when(jiangyiDeviceDirectory.isJiangyi("EG-DOOR")).thenReturn(true);
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("device-service down"))
                .when(deviceServiceClient).requestOpenDoorStock(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong());

        assertThrows(ResponseStatusException.class, () -> service.openStocktakeDoor(1L, "EG-DOOR"));

        org.mockito.Mockito.verify(self).markStocktakeSessionFailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.contains("开门指令下发失败"));
    }

    @Test
    void submitDoorStocktake_duplicateSku_rejectsFailClosed() {
        when(jiangyiDeviceDirectory.isJiangyi("EG-DOOR")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitDoorStocktake(1L, "EG-DOOR", new com.aicabinet.common.dto.MerchantDoorStocktakeRequest(
                        List.of(new com.aicabinet.common.dto.MerchantDoorStocktakeRequest.StocktakeLine("SKU-1", 3),
                                new com.aicabinet.common.dto.MerchantDoorStocktakeRequest.StocktakeLine("SKU-1", 5)),
                        null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        org.mockito.Mockito.verify(inventoryLotService, org.mockito.Mockito.never())
                .stocktakeAdjust(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void submitDoorStocktake_happyPath_adjustsPerLineAndWritesEvent() {
        when(jiangyiDeviceDirectory.isJiangyi("EG-DOOR")).thenReturn(true);
        com.aicabinet.trade.domain.DeviceSkuInventory beforeRow = new com.aicabinet.trade.domain.DeviceSkuInventory();
        beforeRow.setSkuId("SKU-1");
        beforeRow.setQuantity(4);
        when(inventoryRepository.findByIdDeviceId("EG-DOOR")).thenReturn(List.of(beforeRow));
        org.mockito.Mockito.doAnswer(inv -> {
            // stocktakeAdjust 内部会同步聚合台账：mock 同步效果 4 → 7
            beforeRow.setQuantity(7);
            return null;
        }).when(inventoryLotService).stocktakeAdjust(org.mockito.ArgumentMatchers.eq("EG-DOOR"),
                org.mockito.ArgumentMatchers.eq("SKU-1"), org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyString());
        when(inventoryRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(beforeRow));

        com.aicabinet.common.dto.MerchantDoorStocktakeResult result = service.submitDoorStocktake(
                1L, "EG-DOOR", new com.aicabinet.common.dto.MerchantDoorStocktakeRequest(
                        List.of(new com.aicabinet.common.dto.MerchantDoorStocktakeRequest.StocktakeLine("SKU-1", 7)),
                        "https://evidence/a.jpg", null, null));

        assertEquals(1, result.lines().size());
        assertEquals(4, result.lines().get(0).quantityBefore());
        assertEquals(7, result.lines().get(0).quantityAfter());
        assertEquals(3, result.lines().get(0).delta());
        org.mockito.Mockito.verify(opsEventRepository).insert(org.mockito.ArgumentMatchers.argThat(
                (com.aicabinet.trade.domain.DeviceOpsEvent e) ->
                        "AISLE_AUDIT".equals(e.getEventType())
                                && e.getDetail() != null && e.getDetail().contains("SKU-1 4→7（+3）")
                                && e.getDetail().contains("凭证=有")));
        org.mockito.Mockito.verify(merchantOpsPolicyService).requirePhotoEvidence(
                org.mockito.ArgumentMatchers.eq("EG-DOOR"), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("https://evidence/a.jpg"));
    }
}
