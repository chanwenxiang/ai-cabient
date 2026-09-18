package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertDeviceRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
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

    private OpsDeviceAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsDeviceAdminService(
                permissionService, merchantScopeService, deviceRepository, sessionRepository,
                merchantRepository, replenishmentTaskRepository, orderRepository,
                deviceIdService, deviceIdRenameService, deviceSlotService, auditService, refundPolicyService);
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
                        new UpsertDeviceRequest(null, "柜A", null, "M-1", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.DEVICE_LOCATION_REQUIRED, ex.getReason());
        verify(deviceRepository, never()).save(any(DeviceInfo.class));
    }

    /** 坐标范围非法同样拒写，避免落半截点位数据。 */
    @Test
    void createDevice_rejectsOutOfRangeCoords() {
        when(deviceIdService.resolveForCreate(any())).thenReturn("CAB-NEW");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createDevice(10001L,
                        new UpsertDeviceRequest(null, "柜A", null, null, 91.0, 121.0, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.DEVICE_LOCATION_INVALID, ex.getReason());
        verify(deviceRepository, never()).save(any(DeviceInfo.class));
    }
}
