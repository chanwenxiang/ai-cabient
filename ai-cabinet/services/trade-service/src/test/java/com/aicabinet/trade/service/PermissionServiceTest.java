package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.repository.OpsPermissionRepository;
import com.aicabinet.trade.repository.OpsUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    private static final long OPERATOR_ID = CabinetConstants.OPERATOR_USER_ID_START + 1;

    @Mock
    private OpsPermissionRepository permissionRepository;

    @Mock
    private OpsUserRoleRepository userRoleRepository;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(permissionRepository, userRoleRepository);
    }

    @Test
    void noRoles_grantsFullAccessForBackwardCompat() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of());

        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:device:list"));
        assertTrue(permissionService.hasPermission(OPERATOR_ID, "ops:sku:edit"));
    }

    @Test
    void rolesWithoutPermissions_denies() {
        when(userRoleRepository.findByIdUserId(OPERATOR_ID)).thenReturn(List.of(new OpsUserRole()));
        when(permissionRepository.findPermCodesByUserId(OPERATOR_ID)).thenReturn(Set.of());

        assertFalse(permissionService.hasPermission(OPERATOR_ID, "ops:device:list"));
    }

    private static final long CONSUMER_ID = 10001L;

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
}
