package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 关门/回锁下行（V16 §4.3.2.6 可选指令 closeDoor）。 */
class JiangyiDeviceOpsServiceCloseDoorTest {

    private static final String DEVICE_ID = "EG943322621391";
    private static final Long OPERATOR_ID = 100000012L;

    private JiangyiGatewayClient gatewayClient;
    private JiangyiDeviceDirectory directory;
    private DeviceValidationService validationService;
    private AdminAuditService auditService;
    private JiangyiDeviceOpsService service;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(JiangyiGatewayClient.class);
        directory = mock(JiangyiDeviceDirectory.class);
        validationService = mock(DeviceValidationService.class);
        auditService = mock(AdminAuditService.class);
        service = new JiangyiDeviceOpsService(gatewayClient, directory,
                mock(DeviceInfoMapper.class), mock(ShoppingSessionMapper.class), validationService,
                auditService);
    }

    @Test
    void closeDoor_dispatchesAndAudits() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);

        service.closeDoor(OPERATOR_ID, DEVICE_ID, false);

        verify(gatewayClient).closeDoor(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_CLOSE_DOOR"),
                eq("DEVICE"), eq(DEVICE_ID), anyString());
    }

    @Test
    void closeDoor_rejectsNonJiangyiDevice() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.closeDoor(OPERATOR_ID, DEVICE_ID, false));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(gatewayClient, org.mockito.Mockito.times(0)).closeDoor(anyString());
    }

    @Test
    void closeDoor_gatewayFailure_mapsTo503() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        doThrow(new RuntimeException("connect timeout")).when(gatewayClient).closeDoor(DEVICE_ID);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.closeDoor(OPERATOR_ID, DEVICE_ID, false));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
        verify(auditService, org.mockito.Mockito.times(0))
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    /** 有进行中消费者会话/补货任务时不下发；运营可在 UI 二次确认后带 force 重试。 */
    @Test
    void closeDoor_blockedByActiveBusiness() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "设备使用中"))
                .when(validationService).ensureMaintenanceCommandAllowed(DEVICE_ID, false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.closeDoor(OPERATOR_ID, DEVICE_ID, false));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(gatewayClient, org.mockito.Mockito.times(0)).closeDoor(anyString());
        verify(auditService, org.mockito.Mockito.times(0))
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void closeDoor_forceBypassesGuardAndAuditsForce() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        org.mockito.ArgumentCaptor<String> detail =
                org.mockito.ArgumentCaptor.forClass(String.class);

        service.closeDoor(OPERATOR_ID, DEVICE_ID, true);

        verify(validationService).ensureMaintenanceCommandAllowed(DEVICE_ID, true);
        verify(gatewayClient).closeDoor(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_CLOSE_DOOR"),
                eq("DEVICE"), eq(DEVICE_ID), detail.capture());
        org.junit.jupiter.api.Assertions.assertTrue(detail.getValue().contains("强制"));
    }
}
