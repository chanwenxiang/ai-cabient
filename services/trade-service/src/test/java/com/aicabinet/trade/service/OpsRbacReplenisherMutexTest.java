package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.sms.SmsCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G2-12 ⑦ 实测修复的回归钉：平台补货员（replenisher）与商户补货员（merchant_replenisher）
 * 共用小程序、靠商户范围区分任务 ⇒ 二者互斥必须由接口层把守（此前只有前端 saveRoles 的 warning，
 * 直打 PUT /rbac/users/{id}/roles body=[3,11] 返回 200 并落两行）。
 */
@ExtendWith(MockitoExtension.class)
class OpsRbacReplenisherMutexTest {

    private static final long OPERATOR = 1L;
    private static final long TARGET = 100000045L;
    private static final long ROLE_PLATFORM_REPLENISHER = 3L;
    private static final long ROLE_MERCHANT_REPLENISHER = 11L;

    @Mock private com.aicabinet.trade.mapper.OpsRoleMapper roleRepository;
    @Mock private com.aicabinet.trade.mapper.OpsPermissionMapper permissionRepository;
    @Mock private com.aicabinet.trade.mapper.OpsRolePermissionMapper rolePermissionRepository;
    @Mock private com.aicabinet.trade.mapper.OpsUserRoleMapper userRoleRepository;
    @Mock private com.aicabinet.trade.mapper.OpsUserMerchantMapper userMerchantRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private com.aicabinet.trade.mapper.UserInfoMapper userInfoRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private AdminAuditService auditService;
    @Mock private SmsCodeService smsCodeService;

    private OpsRbacService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        service = new OpsRbacService(roleRepository, permissionRepository, rolePermissionRepository,
                userRoleRepository, userMerchantRepository, null, permissionService, merchantScopeService,
                userInfoRepository, null, null, null, distributedLockService, null,
                null, null, null, auditService, smsCodeService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self",
                org.mockito.Mockito.mock(OpsRbacService.class));
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(userInfoRepository.existsById(anyLong())).thenReturn(true);
    }

    private static OpsRole role(long roleId, String roleKey) {
        OpsRole role = new OpsRole();
        role.setRoleId(roleId);
        role.setRoleKey(roleKey);
        return role;
    }

    private void stubReplenisherRoles() {
        lenient().when(roleRepository.findById(ROLE_PLATFORM_REPLENISHER))
                .thenReturn(Optional.of(role(ROLE_PLATFORM_REPLENISHER, "replenisher")));
        lenient().when(roleRepository.findById(ROLE_MERCHANT_REPLENISHER))
                .thenReturn(Optional.of(role(ROLE_MERCHANT_REPLENISHER, "merchant_replenisher")));
    }

    @Test
    void assignRoles_bothReplenishers_rejectedWith400_andNothingWritten() {
        stubReplenisherRoles();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignRoles(OPERATOR, TARGET,
                        List.of(ROLE_PLATFORM_REPLENISHER, ROLE_MERCHANT_REPLENISHER)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("平台补货员与商户补货员"), ex.getReason());
        verify(userRoleRepository, never()).deleteByIdUserId(anyLong());
        verify(userRoleRepository, never()).insert(any());
        verify(auditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void assignRoles_platformReplenisherAlone_stillAllowed() {
        stubReplenisherRoles();

        service.assignRoles(OPERATOR, TARGET, List.of(ROLE_PLATFORM_REPLENISHER));

        verify(userRoleRepository).insert(any());
    }

    @Test
    void assignRoles_merchantReplenisherAlone_stillAllowed() {
        stubReplenisherRoles();

        service.assignRoles(OPERATOR, TARGET, List.of(ROLE_MERCHANT_REPLENISHER));

        verify(userRoleRepository).insert(any());
    }
}
