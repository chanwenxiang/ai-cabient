package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.OpsUserRoleId;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopeMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopePrefMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantScopeServiceTest {

    private static final long MERCHANT_A_USER = 100000002L;
    private static final long MERCHANT_B_USER = 100000004L;
    private static final long ADMIN_USER = 100000001L;

    @Mock
    private OpsUserMerchantMapper userMerchantRepository;
    @Mock
    private OpsUserRoleMapper userRoleRepository;
    @Mock
    private OpsRoleMapper roleRepository;
    @Mock
    private DeviceInfoMapper deviceRepository;
    @Mock
    private MerchantMapper merchantRepository;
    @Mock
    private OpsUserDeviceScopeMapper deviceScopeMapper;
    @Mock
    private OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    @Mock
    private CabinetMetrics cabinetMetrics;

    private MerchantScopeService merchantScopeService;

    @BeforeEach
    void setUp() {
        merchantScopeService = new MerchantScopeService(
                userMerchantRepository, userRoleRepository, roleRepository,
                deviceRepository, merchantRepository, deviceScopeMapper, deviceScopePrefMapper,
                null, cabinetMetrics, null);
        org.springframework.test.util.ReflectionTestUtils.setField(merchantScopeService, "self", merchantScopeService);
        lenient().when(merchantRepository.findAll()).thenReturn(List.of());
        lenient().when(deviceScopePrefMapper.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void adminRole_hasGlobalScope() {
        stubAdminRole(ADMIN_USER);

        assertTrue(merchantScopeService.isGlobalScope(ADMIN_USER));
        assertNull(merchantScopeService.allowedMerchantIds(ADMIN_USER));
        assertDoesNotThrow(() -> merchantScopeService.requireDeviceAccess(ADMIN_USER, "CAB-OTHER"));
    }

    @Test
    void unboundOperator_hasGlobalScope() {
        when(userRoleRepository.findByIdUserId(ADMIN_USER)).thenReturn(List.of());
        when(userMerchantRepository.existsByIdUserId(ADMIN_USER)).thenReturn(false);

        assertTrue(merchantScopeService.isGlobalScope(ADMIN_USER));
        assertNull(merchantScopeService.allowedDeviceIds(ADMIN_USER));
    }

    @Test
    void merchantA_canAccessOwnDevice_notOtherTenant() {
        stubScopedMerchant(MERCHANT_A_USER, "MCH-DEFAULT", device("CAB-001", "MCH-DEFAULT"));

        assertEquals(Set.of("MCH-DEFAULT"), merchantScopeService.allowedMerchantIds(MERCHANT_A_USER));
        assertDoesNotThrow(() -> merchantScopeService.requireDeviceAccess(MERCHANT_A_USER, "CAB-001"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> merchantScopeService.requireDeviceAccess(MERCHANT_A_USER, "CAB-OTHER"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(ApiMessages.PERMISSION_DENIED, ex.getReason());
        verify(cabinetMetrics).recordMerchantScopeDenied("device");
    }

    @Test
    void merchantB_canAccessOtherTenantDevice_notDefault() {
        stubScopedMerchant(MERCHANT_B_USER, "MCH-OTHER", device("CAB-OTHER", "MCH-OTHER"));

        assertDoesNotThrow(() -> merchantScopeService.requireDeviceAccess(MERCHANT_B_USER, "CAB-OTHER"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> merchantScopeService.requireDeviceAccess(MERCHANT_B_USER, "CAB-001"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(cabinetMetrics).recordMerchantScopeDenied("device");
    }

    @Test
    void requireMerchantAccess_deniesCrossTenant() {
        stubScopedMerchantBinding(MERCHANT_A_USER, "MCH-DEFAULT");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> merchantScopeService.requireMerchantAccess(MERCHANT_A_USER, "MCH-OTHER"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(cabinetMetrics).recordMerchantScopeDenied("merchant");
    }

    @Test
    void intersectDeviceFilter_scopesToAllowedDevices() {
        stubAdminRole(ADMIN_USER);
        assertNull(merchantScopeService.intersectDeviceFilter(ADMIN_USER, null));

        stubScopedMerchant(MERCHANT_A_USER, "MCH-DEFAULT", device("CAB-001", "MCH-DEFAULT"));
        assertEquals(Set.of("CAB-001"), merchantScopeService.intersectDeviceFilter(MERCHANT_A_USER, null));
    }

    private void stubAdminRole(long userId) {
        OpsUserRole userRole = new OpsUserRole();
        userRole.setId(new OpsUserRoleId(userId, 1L));
        when(userRoleRepository.findByIdUserId(userId)).thenReturn(List.of(userRole));

        OpsRole adminRole = new OpsRole();
        adminRole.setRoleId(1L);
        adminRole.setRoleKey("admin");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(adminRole));
    }

    private void stubScopedMerchantBinding(long userId, String merchantId) {
        when(userRoleRepository.findByIdUserId(userId)).thenReturn(List.of());
        when(userMerchantRepository.existsByIdUserId(userId)).thenReturn(true);
        when(userMerchantRepository.findByIdUserId(userId))
                .thenReturn(List.of(new OpsUserMerchant(userId, merchantId)));
    }

    private void stubScopedMerchant(long userId, String merchantId, DeviceInfo device) {
        stubScopedMerchantBinding(userId, merchantId);
        when(deviceRepository.findByMerchantIdIn(Set.of(merchantId))).thenReturn(List.of(device));
    }

    private static DeviceInfo device(String deviceId, String merchantId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setDeviceType("AI_CABINET_V1");
        device.setOnlineStatus("OFFLINE");
        device.setMerchantId(merchantId);
        return device;
    }
}
