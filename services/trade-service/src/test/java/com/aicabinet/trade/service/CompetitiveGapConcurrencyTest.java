package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsUserDeviceScopeDto;
import com.aicabinet.common.dto.PhoneVerifyLogDto;
import com.aicabinet.common.dto.DevicePolicyDto;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.mapper.*;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompetitiveGapConcurrencyTest {

    @Mock private OpsUserDeviceScopeMapper deviceScopeMapper;
    @Mock private OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    @Mock private MerchantOpsConfigMapper opsConfigMapper;
    @Mock private MerchantRoleTemplateMapper roleTemplateMapper;
    @Mock private DeviceOpsEventMapper deviceOpsEventMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private PhoneVerifyLogMapper phoneVerifyLogMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private CabinetOrderMapper orderMapper;
    @Mock private CabinetOrderLineMapper lineMapper;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private OpsUserRouteScopeMapper routeScopeMapper;
    @Mock private DistributedLockService distributedLockService;

    private CompetitiveGapService service;

    @BeforeEach
    void setUp() {
        service = new CompetitiveGapService(deviceScopeMapper, deviceScopePrefMapper, opsConfigMapper,
                roleTemplateMapper, deviceOpsEventMapper, deviceInfoMapper, phoneVerifyLogMapper,
                merchantMapper, orderMapper, lineMapper, merchantScopeService, permissionService,
                auditService, salesLockService, new SecurityProperties(false),
                routeScopeMapper, distributedLockService);
    }

    @Test
    void assignUserDeviceScope_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(CompetitiveGapService.opsDeviceScopeLockKey(300L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignUserDeviceScope(1L, 300L,
                        new OpsUserDeviceScopeDto(300L, "ALL", java.util.List.of(), java.util.List.of())));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void saveOpsConfig_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(MerchantService.merchantLockKey("M-100")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveOpsConfig(1L, "M-100",
                        new com.aicabinet.common.dto.MerchantOpsConfigDto(
                                "M-100", "CAPACITY", 50, "INDEPENDENT",
                                true, "ADD_QTY", false, false, 0)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deletePhoneVerify_whenRecordMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(CompetitiveGapService.phoneVerifyLogLockKey(55L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(phoneVerifyLogMapper.findByIdForUpdate(55L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.deletePhoneVerify(1L, 55L));

        verify(distributedLockService).unlock(CompetitiveGapService.phoneVerifyLogLockKey(55L));
    }

    @Test
    void updatePhoneVerify_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(CompetitiveGapService.phoneVerifyLogLockKey(66L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updatePhoneVerify(1L, 66L,
                        new PhoneVerifyLogDto(66L, 1L, "13800000000", "SMS", null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updateDevicePolicy_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceSalesLockService.deviceSalesLockKey("CAB-POLICY")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateDevicePolicy(1L, "CAB-POLICY",
                        new DevicePolicyDto("CAB-POLICY", false, false, false, false)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
