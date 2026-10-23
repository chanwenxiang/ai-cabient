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
    private AdminAuditService auditService;
    private JiangyiDeviceOpsService service;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(JiangyiGatewayClient.class);
        directory = mock(JiangyiDeviceDirectory.class);
        deviceMapper = mock(DeviceInfoMapper.class);
        auditService = mock(AdminAuditService.class);
        service = new JiangyiDeviceOpsService(gatewayClient, directory,
                deviceMapper, mock(ShoppingSessionMapper.class), auditService);
    }

    @Test
    void reboot_dispatchesAndAudits() {
        when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);

        service.reboot(OPERATOR_ID, DEVICE_ID);

        verify(gatewayClient).reboot(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_REBOOT"),
                eq("DEVICE"), eq(DEVICE_ID), anyString());
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
                () -> service.reboot(OPERATOR_ID, DEVICE_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
        verify(auditService, never())
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void recordDoorState_persistsNormalizedSnapshot() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEVICE_ID);
        when(deviceMapper.findById(DEVICE_ID)).thenReturn(Optional.of(device));

        service.recordDoorState(DEVICE_ID, " OPEN ", "Close");

        assertEquals("open", device.getDoorState());
        assertEquals("close", device.getLockState());
        org.junit.jupiter.api.Assertions.assertNotNull(device.getDoorReportedAt());
        verify(deviceMapper).updateById(device);
    }

    /** 固件口径不可信：非法值丢弃，不覆盖既有快照。 */
    @Test
    void recordDoorState_dropsIllegalValues() {
        when(deviceMapper.findById(DEVICE_ID)).thenReturn(Optional.of(new DeviceInfo()));

        service.recordDoorState(DEVICE_ID, "locking", null);

        verify(deviceMapper, times(0)).updateById(any());
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
