package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceLifecycleRequest;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceLifecycleEventMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceAssetConcurrencyTest {

    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private DeviceLifecycleEventMapper lifecycleEventMapper;
    @Mock private DeviceSkuInventoryMapper inventoryMapper;
    @Mock private DeviceSkuLotMapper lotMapper;
    @Mock private SkuCatalogMapper skuCatalogMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private DeviceAssetService service;

    @BeforeEach
    void setUp() {
        service = new DeviceAssetService(deviceInfoMapper, lifecycleEventMapper, inventoryMapper, lotMapper,
                skuCatalogMapper, merchantMapper, merchantScopeService, permissionService, auditService,
                distributedLockService);
    }

    @Test
    void applyLifecycle_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceAssetService.deviceAssetLockKey("DEV-ASSET-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyLifecycle(1L, "DEV-ASSET-1",
                        new DeviceLifecycleRequest("DEPLOY", null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void applyLifecycle_whenDeviceNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(DeviceAssetService.deviceAssetLockKey("DEV-ASSET-2")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(deviceInfoMapper.findByIdForUpdate("DEV-ASSET-2")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.applyLifecycle(1L, "DEV-ASSET-2",
                        new DeviceLifecycleRequest("DEPLOY", null, null)));

        verify(distributedLockService).unlock(DeviceAssetService.deviceAssetLockKey("DEV-ASSET-2"));
    }
}
