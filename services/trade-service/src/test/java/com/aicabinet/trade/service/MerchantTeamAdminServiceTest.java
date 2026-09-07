package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateMerchantUserRequest;
import com.aicabinet.common.dto.MerchantTeamRoleDto;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantTeamAdminServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock UserInfoMapper userInfoRepository;
    @Mock UserAccountMapper userAccountRepository;
    @Mock OpsUserMerchantMapper userMerchantRepository;
    @Mock OpsUserRoleMapper userRoleRepository;
    @Mock OpsRoleMapper roleRepository;
    @Mock AdminAuditService auditService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock OperatorUserIdAllocator operatorUserIdAllocator;
    @Mock DistributedLockService distributedLockService;

    private MerchantTeamAdminService service;

    @BeforeEach
    void setUp() {
        service = new MerchantTeamAdminService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                userInfoRepository, userAccountRepository, userMerchantRepository,
                userRoleRepository, roleRepository, auditService, passwordEncoder,
                operatorUserIdAllocator, distributedLockService);
    }

    @Test
    void g5_resolveMerchantRoleId_matchesHardcodedSeedIds() {
        assertEquals(6L, service.resolveMerchantRoleId("merchant"));
        assertEquals(6L, service.resolveMerchantRoleId("merchant_admin"));
        assertEquals(7L, service.resolveMerchantRoleId("merchant_staff"));
        assertEquals(8L, service.resolveMerchantRoleId("merchant_finance"));
        assertEquals(10L, service.resolveMerchantRoleId("merchant_store_manager"));
        assertEquals(11L, service.resolveMerchantRoleId("merchant_replenisher"));
        assertEquals(MerchantTeamAdminService.MERCHANT_STAFF_ROLE_ID, service.resolveMerchantRoleId(null));
        assertEquals(MerchantTeamAdminService.MERCHANT_ROLE_ID, MerchantTeamAdminService.MERCHANT_ROLE_ID);
    }

    @Test
    void resolveMerchantRoleId_unknown_rejects() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resolveMerchantRoleId("ops_admin"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void listTeamRoles_returnsFiveFixedRoles() {
        List<MerchantTeamRoleDto> roles = service.listTeamRoles(1L);
        assertEquals(5, roles.size());
        assertTrue(roles.stream().anyMatch(r -> "merchant".equals(r.roleKey())));
        assertTrue(roles.stream().anyMatch(r -> "店长".equals(r.roleName())));
    }

    @Test
    void createTeamUser_whenPhoneLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantTeamAdminService.merchantTeamPhoneLockKey("13900009999"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createTeamUser(1L,
                        new CreateMerchantUserRequest("13900009999", "pass12", "成员", "merchant_staff")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void merchantTeamPhoneLockKey_facadeAliases() {
        assertEquals(
                MerchantTeamAdminService.merchantTeamPhoneLockKey("13900001111"),
                MerchantPortalService.merchantTeamPhoneLockKey("13900001111"));
    }
}
