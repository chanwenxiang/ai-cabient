package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceOpsCommandRequest;
import com.aicabinet.common.dto.DeviceOpsCommandResultDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDeviceOpsServiceLockMessageTest {

    private static final long OPERATOR_ID = 100_000_001L;
    private static final String DEVICE_ID = "CAB-001";

    @Mock
    private DeviceInfoMapper deviceRepository;
    @Mock
    private ShoppingSessionMapper sessionRepository;
    @Mock
    private DeviceValidationService deviceValidationService;
    @Mock
    private DeviceSalesLockService salesLockService;
    @Mock
    private DeviceServiceClient deviceClient;
    @Mock
    private MerchantScopeService merchantScopeService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private AdminAuditService auditService;
    @Mock
    private DistributedLockService distributedLockService;

    private AdminDeviceOpsService service;

    @BeforeEach
    void setUp() {
        service = new AdminDeviceOpsService(
                deviceRepository,
                sessionRepository,
                deviceValidationService,
                salesLockService,
                deviceClient,
                merchantScopeService,
                permissionService,
                auditService,
                distributedLockService);
    }

    @Test
    void lockCommand_messageReflectsLockEvenWhenCallerDeviceObjectStale() {
        DeviceInfo staleUnlocked = device(false);
        when(deviceRepository.findById(DEVICE_ID)).thenReturn(Optional.of(staleUnlocked));
        // applySalesLock updates DB via its own reload; does not mutate the caller-held entity
        when(salesLockService.applySalesLock(eq(OPERATOR_ID), eq(staleUnlocked), eq(true), anyString(), eq(true)))
                .thenReturn("CMD-LOCK");

        DeviceOpsCommandResultDto result = service.execute(
                OPERATOR_ID, DEVICE_ID, new DeviceOpsCommandRequest("LOCK", "巡检锁机", null));

        assertEquals("LOCK", result.command());
        assertTrue(result.salesLocked());
        assertEquals("已锁机，消费者无法开门", result.message());
        assertFalse(staleUnlocked.salesLockedEnabled(), "caller entity may stay stale; DTO must still be correct");
    }

    @Test
    void unlockCommand_messageReflectsUnlockEvenWhenCallerDeviceObjectStale() {
        DeviceInfo staleLocked = device(true);
        when(deviceRepository.findById(DEVICE_ID)).thenReturn(Optional.of(staleLocked));
        when(salesLockService.applySalesLock(eq(OPERATOR_ID), eq(staleLocked), eq(false), anyString(), eq(true)))
                .thenReturn("CMD-UNLOCK");

        DeviceOpsCommandResultDto result = service.execute(
                OPERATOR_ID, DEVICE_ID, new DeviceOpsCommandRequest("UNLOCK", "恢复营业", null));

        assertEquals("UNLOCK", result.command());
        assertFalse(result.salesLocked());
        assertEquals("已解锁，恢复营业", result.message());
    }

    private static DeviceInfo device(boolean salesLocked) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(DEVICE_ID);
        d.setSalesLocked(salesLocked);
        return d;
    }
}
