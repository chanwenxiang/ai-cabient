package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceOpsCommandRequest;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDeviceOpsConcurrencyTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private DeviceServiceClient deviceClient;

    private AdminDeviceOpsService service;

    @BeforeEach
    void setUp() {
        service = new AdminDeviceOpsService(deviceRepository, sessionRepository, deviceValidationService,
                salesLockService, deviceClient, merchantScopeService, permissionService, auditService,
                distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void remoteOpen_whenSessionOpenLockBusy_rejectsWithConflict() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-OPS-1");
        when(deviceRepository.findById("CAB-OPS-1")).thenReturn(java.util.Optional.of(device));
        when(distributedLockService.tryLock(
                SessionService.sessionOpenLockKey("CAB-OPS-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.execute(1L, "CAB-OPS-1", new DeviceOpsCommandRequest("OPEN_DOOR", "test", null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void remoteOpen_whenRoutingRejects_passesThroughOriginalStatusAndReason() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-OPS-1");
        when(deviceRepository.findById("CAB-OPS-1")).thenReturn(java.util.Optional.of(device));
        when(distributedLockService.tryLock(
                SessionService.sessionOpenLockKey("CAB-OPS-1"), 60L, 5L))
                .thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "将邑柜不支持运维开门"))
                .when(deviceClient).requestOpenDoorOperator(anyString(), eq("CAB-OPS-1"), eq(1L));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.execute(1L, "CAB-OPS-1", new DeviceOpsCommandRequest("OPEN_DOOR", "test", null)));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        assertEquals("将邑柜不支持运维开门", ex.getReason());
        verify(distributedLockService).unlock(SessionService.sessionOpenLockKey("CAB-OPS-1"));
    }
}
