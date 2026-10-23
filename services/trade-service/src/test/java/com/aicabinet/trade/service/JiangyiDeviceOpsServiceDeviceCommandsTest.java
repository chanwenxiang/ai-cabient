package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 重启（§4.3.2.1）与门磁查询（§4.3.2.12 / 回执 §4.2.16 落库）。 */
class JiangyiDeviceOpsServiceDeviceCommandsTest {

    private static final String DEVICE_ID = "EG943322621391";
    private static final Long OPERATOR_ID = 100000012L;

    private JiangyiGatewayClient gatewayClient;
    private JiangyiDeviceDirectory directory;
    private DeviceInfoMapper deviceMapper;
    private DeviceValidationService validationService;
    private AdminAuditService auditService;
    private JiangyiDeviceOpsService service;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(JiangyiGatewayClient.class);
        directory = mock(JiangyiDeviceDirectory.class);
        deviceMapper = mock(DeviceInfoMapper.class);
        validationService = mock(DeviceValidationService.class);
        auditService = mock(AdminAuditService.class);
        service = new JiangyiDeviceOpsService(gatewayClient, directory,
                deviceMapper, mock(ShoppingSessionMapper.class), validationService, auditService);
    }

    @Test
    void reboot_dispatchesAndAudits() {
        when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);

        service.reboot(OPERATOR_ID, DEVICE_ID, false);

        verify(gatewayClient).reboot(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_REBOOT"),
                eq("DEVICE"), eq(DEVICE_ID), anyString());
    }

    @Test
    void reboot_blockedByActiveBusiness() {
        when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "设备使用中"))
                .when(validationService).ensureMaintenanceCommandAllowed(DEVICE_ID, false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.reboot(OPERATOR_ID, DEVICE_ID, false));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(gatewayClient, never()).reboot(DEVICE_ID);
        verify(auditService, never())
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reboot_forceBypassesGuardAndAuditsForce() {
        when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        org.mockito.ArgumentCaptor<String> detail =
                org.mockito.ArgumentCaptor.forClass(String.class);

        service.reboot(OPERATOR_ID, DEVICE_ID, true);

        verify(validationService).ensureMaintenanceCommandAllowed(DEVICE_ID, true);
        verify(gatewayClient).reboot(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_REBOOT"),
                eq("DEVICE"), eq(DEVICE_ID), detail.capture());
        org.junit.jupiter.api.Assertions.assertTrue(detail.getValue().contains("强制"));
    }

    @Test
    void requestDoorState_dispatchesAndAudits() {
        when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);

        service.requestDoorState(OPERATOR_ID, DEVICE_ID);

        verify(gatewayClient).requestDoorState(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_DOOR_STATE"),
                eq("DEVICE"), eq(DEVICE_ID), anyString());
    }

    @Test
    void reboot_gatewayFailure_mapsTo503() {
        when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        doThrow(new RuntimeException("device offline")).when(gatewayClient).reboot(DEVICE_ID);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.reboot(OPERATOR_ID, DEVICE_ID, false));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
        verify(auditService, never())
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void recordDoorState_persistsNormalizedSnapshot() {
        when(deviceMapper.updateDoorState(eq(DEVICE_ID), eq("open"), eq("close"), any()))
                .thenReturn(1);

        service.recordDoorState(DEVICE_ID, " OPEN ", "Close");

        verify(deviceMapper).updateDoorState(eq(DEVICE_ID), eq("open"), eq("close"), any());
    }

    /**
     * 固件 uploadDoorState 的初值就是 "unknown"（LockSys 读不到 GPIO 时照发）：
     * 必须收下并盖时间戳，否则设备明明回了、后台仍显示「未上报」。
     */
    @Test
    void recordDoorState_acceptsUnknownAsReported() {
        when(deviceMapper.updateDoorState(eq(DEVICE_ID), eq("unknown"), eq("unknown"), any()))
                .thenReturn(1);

        service.recordDoorState(DEVICE_ID, "unknown", "Unknown");

        verify(deviceMapper).updateDoorState(eq(DEVICE_ID), eq("unknown"), eq("unknown"), any());
    }

    /** 只一侧可识别时，另一侧传 null，保留库里上次值。 */
    @Test
    void recordDoorState_keepsOtherSideWhenOneValueUnusable() {
        when(deviceMapper.updateDoorState(eq(DEVICE_ID), isNull(), eq("close"), any()))
                .thenReturn(1);

        service.recordDoorState(DEVICE_ID, "locking", "close");

        verify(deviceMapper).updateDoorState(eq(DEVICE_ID), isNull(), eq("close"), any());
    }

    /** 固件口径不可信：非法值丢弃，不覆盖既有快照。 */
    @Test
    void recordDoorState_dropsIllegalValues() {
        service.recordDoorState(DEVICE_ID, "locking", null);

        verify(deviceMapper, times(0)).updateDoorState(any(), any(), any(), any());
    }

    @Test
    void doorState_returnsEmptyViewWhenNeverReported() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEVICE_ID);
        when(deviceMapper.findById(DEVICE_ID)).thenReturn(Optional.of(device));

        JiangyiDeviceOpsService.DoorStateView view = service.doorState(DEVICE_ID);

        assertNull(view.doorState());
        assertNull(view.lockState());
        assertNull(view.reportedAt());
    }
}
