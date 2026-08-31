package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceLifecycleRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceLifecycleEventMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceAssetLifecycleTest {

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
                distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void bind_whenDeployed_rejects() {
        DeviceInfo device = device("DEV-1", "DEPLOYED", "MCH-A");
        when(deviceInfoMapper.findByIdForUpdate("DEV-1")).thenReturn(Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyLifecycle(1L, "DEV-1",
                        new DeviceLifecycleRequest("BIND", "MCH-B", null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("先解绑"));
        verify(deviceInfoMapper, never()).save(any());
    }

    @Test
    void deploy_whenDeployed_rejects() {
        DeviceInfo device = device("DEV-1", "DEPLOYED", "MCH-A");
        when(deviceInfoMapper.findByIdForUpdate("DEV-1")).thenReturn(Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyLifecycle(1L, "DEV-1",
                        new DeviceLifecycleRequest("DEPLOY", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("无需重复投放"));
    }

    @Test
    void deploy_whenIdleWithoutMerchant_rejects() {
        DeviceInfo device = device("DEV-1", "IDLE", null);
        when(deviceInfoMapper.findByIdForUpdate("DEV-1")).thenReturn(Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyLifecycle(1L, "DEV-1",
                        new DeviceLifecycleRequest("DEPLOY", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("先绑定商户"));
    }

    @Test
    void bind_whenInbound_succeedsAndDeploys() {
        DeviceInfo device = device("DEV-1", "INBOUND", null);
        when(deviceInfoMapper.findByIdForUpdate("DEV-1")).thenReturn(Optional.of(device));
        when(merchantMapper.existsById("MCH-DEFAULT")).thenReturn(true);

        DeviceInfo result = service.applyLifecycle(1L, "DEV-1",
                new DeviceLifecycleRequest("BIND", "MCH-DEFAULT", null));

        assertEquals("DEPLOYED", result.getLifecycleStatus());
        assertEquals("MCH-DEFAULT", result.getMerchantId());
        ArgumentCaptor<DeviceInfo> captor = ArgumentCaptor.forClass(DeviceInfo.class);
        verify(deviceInfoMapper).save(captor.capture());
        assertEquals("DEPLOYED", captor.getValue().getLifecycleStatus());
        verify(lifecycleEventMapper).insert(any());
        verify(auditService).appendLog(eq(1L), eq("DEVICE_LIFECYCLE"), eq("DEVICE"), eq("DEV-1"), anyString());
    }

    @Test
    void unbind_whenDeployed_clearsMerchantAndIdles() {
        DeviceInfo device = device("DEV-1", "DEPLOYED", "MCH-A");
        when(deviceInfoMapper.findByIdForUpdate("DEV-1")).thenReturn(Optional.of(device));

        DeviceInfo result = service.applyLifecycle(1L, "DEV-1",
                new DeviceLifecycleRequest("UNBIND", null, null));

        assertEquals("IDLE", result.getLifecycleStatus());
        assertNull(result.getMerchantId());
    }

    private static DeviceInfo device(String id, String lifecycle, String merchantId) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setLifecycleStatus(lifecycle);
        d.setMerchantId(merchantId);
        return d;
    }
}
