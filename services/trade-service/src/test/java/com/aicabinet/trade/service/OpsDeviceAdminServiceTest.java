package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertDeviceRequest;
import com.aicabinet.common.enums.RefundPolicy;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceLifecycleEvent;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceDailyOnlineRateMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceLifecycleEventMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsDeviceAdminServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantScopeService merchantScopeService;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock MerchantMapper merchantRepository;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock DeviceIdService deviceIdService;
    @Mock DeviceIdRenameService deviceIdRenameService;
    @Mock DeviceSlotService deviceSlotService;
    @Mock AdminAuditService auditService;
    @Mock RefundPolicyService refundPolicyService;
    @Mock WarehouseMapper warehouseRepository;
    @Mock DeviceDailyOnlineRateMapper onlineRateRepository;
    @Mock DeviceLifecycleEventMapper lifecycleEventMapper;
    @Mock JiangyiDeviceDirectory jiangyiDeviceDirectory;

    private OpsDeviceAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsDeviceAdminService(
                permissionService, merchantScopeService, deviceRepository, sessionRepository,
                merchantRepository, replenishmentTaskRepository, orderRepository,
                deviceIdService, deviceIdRenameService, deviceSlotService, auditService, refundPolicyService,
                warehouseRepository, onlineRateRepository, lifecycleEventMapper, jiangyiDeviceDirectory);
    }

    @Test
    void appendRenumberRemark_emptyExisting() {
        assertEquals("编号已由 CAB-OLD 更换为 CAB-NEW",
                OpsDeviceAdminService.appendRenumberRemark(null, "CAB-OLD", "CAB-NEW"));
        assertEquals("编号已由 CAB-OLD 更换为 CAB-NEW",
                OpsDeviceAdminService.appendRenumberRemark("  ", "CAB-OLD", "CAB-NEW"));
    }

    @Test
    void appendRenumberRemark_appendsToExisting() {
        assertEquals("入库备注；编号已由 A 更换为 B",
                OpsDeviceAdminService.appendRenumberRemark("入库备注", "A", "B"));
    }

    @Test
    void regenerateDeviceId_rejectsNonInbound() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setLifecycleStatus("DEPLOYED");

        when(deviceRepository.findByIdForUpdate("CAB-001")).thenReturn(Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.regenerateDeviceId(10001L, "CAB-001"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("入库"));
        verify(deviceIdRenameService, never()).renameInPlace(anyString(), anyString());
        verify(deviceIdService, never()).allocateRandomDeviceId();
    }

    /**
     * 坐标必填：选商户 = 直接部署，此时没有点位坐标的柜机会产出「永久免定位」的签到，
     * 因此在建柜阶段就 fail-closed（这是本判据生效的负向证明）。
     */
    @Test
    void createDevice_rejectsDeployWithoutCoords() {
        when(deviceIdService.resolveForCreate(any())).thenReturn("CAB-NEW");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createDevice(10001L,
                        new UpsertDeviceRequest(null, "柜A", null, "M-1", null, null, null, null, "OFFICE")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.DEVICE_LOCATION_REQUIRED, ex.getReason());
        verify(deviceRepository, never()).save(any(DeviceInfo.class));
    }

    /** 投放场景新建必填：未选场景直接拒写，避免新柜落库即「未分类」。 */
    @Test
    void createDevice_rejectsMissingSceneType() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createDevice(10001L,
                        new UpsertDeviceRequest(null, "柜A", null, "M-1", 30.1, 120.2, null, null, "  ")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("请选择投放场景", ex.getReason());
        verify(deviceRepository, never()).save(any(DeviceInfo.class));
    }

    /** 坐标范围非法同样拒写，避免落半截点位数据。 */
    @Test
    void createDevice_rejectsOutOfRangeCoords() {
        when(deviceIdService.resolveForCreate(any())).thenReturn("CAB-NEW");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createDevice(10001L,
                        new UpsertDeviceRequest(null, "柜A", null, null, 91.0, 121.0, null, null, "OFFICE")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.DEVICE_LOCATION_INVALID, ex.getReason());
        verify(deviceRepository, never()).save(any(DeviceInfo.class));
    }

    /** 只入库：补一条 fromStatus 为空的初始流水，详情「投放流水」不再是空页。 */
    @Test
    void createDevice_inbound_recordsInitialLifecycleEvent() {
        when(deviceIdService.resolveForCreate(any())).thenReturn("CAB-NEW");
        when(refundPolicyService.resolveForDevice("CAB-NEW")).thenReturn(RefundPolicy.AUTO_REFUND);

        service.createDevice(10001L,
                new UpsertDeviceRequest(null, "柜A", null, null, null, null, null, null, "OFFICE"));

        var captor = org.mockito.ArgumentCaptor.forClass(DeviceLifecycleEvent.class);
        verify(lifecycleEventMapper).insert(captor.capture());
        DeviceLifecycleEvent event = captor.getValue();
        assertEquals("CAB-NEW", event.getDeviceId());
        assertNull(event.getFromStatus());
        assertEquals("INBOUND", event.getToStatus());
        assertEquals("INBOUND", event.getAction());
        assertEquals(10001L, event.getOperatorId());
    }

    /** 绑定商户即投放：流水从入库态迁到投放态，行动词与手工投放保持一致。 */
    @Test
    void createDevice_deployed_recordsDeployLifecycleEvent() {
        when(deviceIdService.resolveForCreate(any())).thenReturn("CAB-NEW");
        when(merchantRepository.existsById("M-1")).thenReturn(true);
        when(refundPolicyService.resolveForDevice("CAB-NEW")).thenReturn(RefundPolicy.AUTO_REFUND);

        service.createDevice(10001L,
                new UpsertDeviceRequest(null, "柜A", null, "M-1", 30.1, 120.2, null, null, "OFFICE"));

        var captor = org.mockito.ArgumentCaptor.forClass(DeviceLifecycleEvent.class);
        verify(lifecycleEventMapper).insert(captor.capture());
        DeviceLifecycleEvent event = captor.getValue();
        assertEquals("INBOUND", event.getFromStatus());
        assertEquals("DEPLOYED", event.getToStatus());
        assertEquals("DEPLOY", event.getAction());
    }

    /** 列表回填将邑厂商 SN：一次批量查询，前端 IMEI 列据此区分「厂商未提供」与「无」。 */
    @Test
    void listDevicesPaged_fillsJiangyiSnFromDirectory() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("柜A");
        device.setDeviceType("VISUAL");
        device.setOnlineStatus("OFFLINE");
        when(merchantScopeService.allowedDevices(10001L)).thenReturn(List.of(device));
        when(replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(sessionRepository.findByStateIn(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(jiangyiDeviceDirectory.deviceSnByDeviceIds(List.of("CAB-001")))
                .thenReturn(java.util.Map.of("CAB-001", "YPKJ-SN-1"));
        when(refundPolicyService.resolveForDevice("CAB-001")).thenReturn(RefundPolicy.AUTO_REFUND);

        var result = service.listDevicesPaged(10001L, 0, 50, null, null);

        assertEquals(1, result.items().size());
        assertEquals("YPKJ-SN-1", result.items().get(0).jiangyiSn());
    }

    // ===== CB-018①：柜机×日营收序列 =====
    @Test
    @org.junit.jupiter.api.DisplayName("CB-018①：聚合行转 DTO（含 BigDecimal→long 收敛），窗口与设备透传正确")
    void deviceDailyRevenue_mapsRowsToDto() {
        when(merchantScopeService.allowedDeviceIds(10001L)).thenReturn(null);
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("c0", "CAB-001");
        row.put("c1", java.sql.Date.valueOf("2026-10-08"));
        row.put("c2", new java.math.BigDecimal("350"));
        row.put("c3", 3L);
        when(orderRepository.selectRevenueDailyByDeviceSince(any(), any()))
                .thenReturn(List.of(row));

        var result = service.deviceDailyRevenue(10001L, null, 7);

        assertEquals(1, result.size());
        assertEquals("CAB-001", result.get(0).deviceId());
        assertEquals("2026-10-08", result.get(0).date());
        assertEquals(350L, result.get(0).revenueCents());
        assertEquals(3L, result.get(0).orderCount());
        var idsCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(orderRepository).selectRevenueDailyByDeviceSince(idsCaptor.capture(), any());
        assertTrue(idsCaptor.getValue() == null); // scope null ⇒ 全量（deviceIds=null 不过滤）
    }

    @Test
    @org.junit.jupiter.api.DisplayName("CB-018①：scope 空集合 ⇒ 空列表且不查库")
    void deviceDailyRevenue_emptyScope_returnsEmpty() {
        when(merchantScopeService.allowedDeviceIds(10001L)).thenReturn(java.util.Set.of());

        assertTrue(service.deviceDailyRevenue(10001L, null, 7).isEmpty());
        verify(orderRepository, never()).selectRevenueDailyByDeviceSince(any(), any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("CB-018①：scope 外设备号深链 ⇒ 空列表不 404（防探测），不查库")
    void deviceDailyRevenue_outOfScopeDeepLink_returnsEmpty() {
        when(merchantScopeService.allowedDeviceIds(10001L)).thenReturn(java.util.Set.of("D-1"));

        assertTrue(service.deviceDailyRevenue(10001L, "D-OTHER", 7).isEmpty());
        verify(orderRepository, never()).selectRevenueDailyByDeviceSince(any(), any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("CB-018①：scope 内设备号深链 ⇒ mapper 只收该设备")
    void deviceDailyRevenue_inScopeDeepLink_filtersToDevice() {
        when(merchantScopeService.allowedDeviceIds(10001L)).thenReturn(java.util.Set.of("D-1", "D-2"));
        when(orderRepository.selectRevenueDailyByDeviceSince(any(), any())).thenReturn(List.of());

        service.deviceDailyRevenue(10001L, "D-1", 30);

        @SuppressWarnings("unchecked")
        var idsCaptor = (org.mockito.ArgumentCaptor<java.util.Collection<String>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(orderRepository).selectRevenueDailyByDeviceSince(idsCaptor.capture(), any());
        assertEquals(List.of("D-1"), idsCaptor.getValue());
    }
}
