package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceOpsCommandRequest;
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
import static org.mockito.ArgumentMatchers.eq;
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

    private AdminDeviceOpsService service;

    @BeforeEach
    void setUp() {
        service = new AdminDeviceOpsService(deviceRepository, sessionRepository, deviceValidationService,
                salesLockService, null, merchantScopeService, permissionService, auditService, distributedLockService);
    }

    @Test
    void remoteOpen_whenSessionOpenLockBusy_rejectsWithConflict() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-OPS-1");
        when(deviceRepository.findById("CAB-OPS-1")).thenReturn(java.util.Optional.of(device));
        when(distributedLockService.tryLock(
                eq(SessionService.sessionOpenLockKey("CAB-OPS-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.execute(1L, "CAB-OPS-1", new DeviceOpsCommandRequest("OPEN_DOOR", "test", null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
