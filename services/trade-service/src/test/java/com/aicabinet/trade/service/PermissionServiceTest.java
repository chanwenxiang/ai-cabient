package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    private static final long OPERATOR_ID = CabinetConstants.OPERATOR_USER_ID_START + 1;
    private static final long CONSUMER_ID = 10001L;

    @Mock
    private OpsPermissionMapper permissionRepository;

    @Mock
    private OpsUserRoleMapper userRoleRepository;

    @Mock
    private MerchantFeaturePackService merchantFeaturePackService;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(
                permissionRepository, userRoleRepository, merchantFeaturePackService);
        lenient().when(merchantFeaturePackService.isPermEnabledForUser(anyLong(), anyString()))
                .thenReturn(true);
    }

    @Test
    void noRoles_deniesByDefault() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of());

        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:device:list"));
        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:sku:edit"));
    }

    @Test
    void rolesWithoutPermissions_denies() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID)).thenReturn(Set.of());

        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:device:list"));
    }

    @Test
    void exactPermission_grantsAccess() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:device:list"));

        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:device:list"));
        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:sku:edit"));
    }

    @Test
    void wildcardSuffix_grantsModuleAccess() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:device:*"));

        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:device:edit"));
        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:sku:edit"));
    }

    @Test
    void unrelatedWildcard_doesNotGrantAccess() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("merchant:orders:*"));

        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:device:list"));
    }

    @Test
    void nestedWildcard_grantsButtonAccess() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:rbac:role:*"));

        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:rbac:role:add"));
        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:rbac:menu:add"));
    }

    @Test
    void opsAdmin_grantsAll() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:admin"));

        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:rbac:role:add"));
        assertTrue(permissionService.hasPermission(OPERATOR_ID, "merchant:devices:edit"));
    }

    @Test
    void consumerUser_denied() {
        assertFalse(permissionService.hasPermission(CONSUMER_ID, "ops:device:list"));
    }

    @Test
    void requirePermission_throwsWhenDenied() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID)).thenReturn(Set.of());

        assertThrows(ResponseStatusException.class,
                () -> permissionService.requirePermission(OPERATOR_ID, "ops:order:list"));
    }

    @Test
    void exportCodes_exactAndDenied() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:order:export", "ops:device:list"));

        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:order:export"));
        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:session:export"));
        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:sku:import"));
    }

    @Test
    void requireAnyPermission_importOrEdit() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:sku:import"));

        assertDoesNotThrow(() ->
                permissionService.requireAnyPermission(OPERATOR_ID, "ops:sku:edit", "ops:sku:import"));
        assertThrows(ResponseStatusException.class, () ->
                permissionService.requireAnyPermission(OPERATOR_ID, "ops:dict:edit", "ops:dict:import"));
    }

    @Test
    void enrollStyle_requiresBothSkuEditAndVisionEdit() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID))
                .thenReturn(Set.of("ops:sku:edit", "ops:vision:edit"));

        assertDoesNotThrow(() -> permissionService.requirePermission(OPERATOR_ID, "ops:sku:edit"));
        assertDoesNotThrow(() -> permissionService.requirePermission(OPERATOR_ID, "ops:vision:edit"));
        assertThrows(ResponseStatusException.class,
                () -> permissionService.requirePermission(OPERATOR_ID, "ops:sku:import"));
    }
}
