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
    private AdminAuditService auditService;
    private JiangyiDeviceOpsService service;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(JiangyiGatewayClient.class);
        directory = mock(JiangyiDeviceDirectory.class);
        auditService = mock(AdminAuditService.class);
        service = new JiangyiDeviceOpsService(gatewayClient, directory,
                mock(DeviceInfoMapper.class), mock(ShoppingSessionMapper.class), auditService);
    }

    @Test
    void closeDoor_dispatchesAndAudits() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);

        service.closeDoor(OPERATOR_ID, DEVICE_ID);

        verify(gatewayClient).closeDoor(DEVICE_ID);
        verify(auditService).appendLog(eq(OPERATOR_ID), eq("JIANGYI_CLOSE_DOOR"),
                eq("DEVICE"), eq(DEVICE_ID), anyString());
    }

    @Test
    void closeDoor_rejectsNonJiangyiDevice() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.closeDoor(OPERATOR_ID, DEVICE_ID));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(gatewayClient, org.mockito.Mockito.times(0)).closeDoor(anyString());
    }

    @Test
    void closeDoor_gatewayFailure_mapsTo503() {
        org.mockito.Mockito.when(directory.isJiangyi(DEVICE_ID)).thenReturn(true);
        doThrow(new RuntimeException("connect timeout")).when(gatewayClient).closeDoor(DEVICE_ID);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.closeDoor(OPERATOR_ID, DEVICE_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
        verify(auditService, org.mockito.Mockito.times(0))
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }
}
