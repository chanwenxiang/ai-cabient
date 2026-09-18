package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateOpsPermissionRequest;
import com.aicabinet.common.dto.OpsOperatorDto;
import com.aicabinet.common.dto.ResetOpsOperatorPasswordRequest;
import com.aicabinet.common.dto.UpdateOpsMeRequest;
import com.aicabinet.common.dto.UpdateOpsOperatorRequest;
import com.aicabinet.trade.domain.OpsPermission;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsDepartmentMapper;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsRolePermissionMapper;
import com.aicabinet.trade.mapper.OpsUserDepartmentMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C14 / C15 / C16 / H65：运营换绑手机短信校验、保留权限禁分配、超管保护、通配权限码禁创建。
 */
@ExtendWith(MockitoExtension.class)
class OpsRbacServiceGuardTest {

    private static final Long ACTOR = 100_000_001L;
    private static final Long TARGET = 100_000_002L;

    @Mock private OpsRoleMapper roleRepository;
    @Mock private OpsPermissionMapper permissionRepository;
    @Mock private OpsRolePermissionMapper rolePermissionRepository;
    @Mock private OpsUserRoleMapper userRoleRepository;
    @Mock private OpsUserMerchantMapper userMerchantRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OpsUserDepartmentMapper userDepartmentRepository;
    @Mock private OpsDepartmentMapper departmentRepository;
    @Mock private AdminAuditService auditService;
    @Mock private SmsCodeService smsCodeService;

    private OpsRbacService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // lenient：sendPhoneChangeCode 用 requireOperator，不经过 requirePermission
        org.mockito.Mockito.lenient().doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        service = new OpsRbacService(roleRepository, permissionRepository, rolePermissionRepository,
                userRoleRepository, userMerchantRepository, null, permissionService, merchantScopeService,
                userInfoRepository, null, null, null, distributedLockService, null,
                userDepartmentRepository, departmentRepository, null, auditService, smsCodeService, null);
    }

    private void allowLock(String key) {
        when(distributedLockService.tryLock(key, 60L, 5L)).thenReturn(true);
    }

    private static OpsRole role(long roleId, String roleKey) {
        OpsRole role = new OpsRole();
        role.setRoleId(roleId);
        role.setRoleKey(roleKey);
        return role;
    }

    private static OpsPermission permission(long id, String code) {
        OpsPermission p = new OpsPermission();
        p.setPermissionId(id);
        p.setPermCode(code);
        return p;
    }

    private static UserInfo operator(long userId, String phone) {
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setPhoneNumber(phone);
        user.setName("运营");
        user.setStatus("ACTIVE");
        user.setAccountType("OPERATOR");
        return user;
    }

    // ── C15：保留权限不可分配给自定义角色 ─────────────────────────────────

    @Test
    void assignRolePermissions_rejectsOpsAdminForCustomRole() {
        allowLock(OpsRbacService.opsRoleLockKey(5L));
        when(roleRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(role(5L, "CUSTOM")));
        doNothing().when(rolePermissionRepository).deleteByIdRoleId(5L);
        when(permissionRepository.findById(7L)).thenReturn(Optional.of(permission(7L, "ops:admin")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignRolePermissions(ACTOR, 5L, List.of(7L)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("保留权限不可分配给自定义角色", ex.getReason());
        verify(rolePermissionRepository, never()).insert(any());
    }

    @Test
    void assignRolePermissions_rejectsWildcardCodeForCustomRole() {
        allowLock(OpsRbacService.opsRoleLockKey(5L));
        when(roleRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(role(5L, "CUSTOM")));
        doNothing().when(rolePermissionRepository).deleteByIdRoleId(5L);
        when(permissionRepository.findById(8L)).thenReturn(Optional.of(permission(8L, "ops:user:*")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignRolePermissions(ACTOR, 5L, List.of(8L)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("保留权限不可分配给自定义角色", ex.getReason());
    }

    // ── C16：超管禁用/重置保护 ────────────────────────────────────────────

    @Test
    void disableOperator_rejectsAdminTarget() {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userRoleRepository.findByIdUserId(TARGET)).thenReturn(List.of(new OpsUserRole(TARGET, 1L)));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, "admin")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.disableOperator(ACTOR, TARGET));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("不允许禁用/重置超级管理员账号", ex.getReason());
        verify(userInfoRepository, never()).save(any());
    }

    @Test
    void resetOperatorPassword_rejectsAdminTarget() {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userRoleRepository.findByIdUserId(TARGET)).thenReturn(List.of(new OpsUserRole(TARGET, 1L)));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, "admin")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetOperatorPassword(ACTOR, TARGET, new ResetOpsOperatorPasswordRequest("newpass1")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("不允许禁用/重置超级管理员账号", ex.getReason());
    }

    @Test
    void disableOperator_nonAdmin_targetAudited() {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userRoleRepository.findByIdUserId(TARGET)).thenReturn(List.of());
        when(userInfoRepository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(operator(TARGET, "13900000002")));

        service.disableOperator(ACTOR, TARGET);

        verify(userInfoRepository).save(any());
        verify(auditService).appendLog(eq(ACTOR), eq("OPS_OPERATOR_DISABLE"),
                eq("USER"), eq(String.valueOf(TARGET)), any());
    }

    // ── H65：运行时禁止创建通配 / 内置权限码 ─────────────────────────────

    @Test
    void createPermission_rejectsWildcardCode() {
        allowLock(OpsRbacService.opsPermissionCodeLockKey("ops:user:*"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createPermission(ACTOR, new CreateOpsPermissionRequest(
                        0L, "ops:user:*", "用户通配", "F", null, 1, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(permissionRepository, never()).save(any());
    }

    @Test
    void createPermission_rejectsReservedOpsAdminCode() {
        allowLock(OpsRbacService.opsPermissionCodeLockKey("ops:admin"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createPermission(ACTOR, new CreateOpsPermissionRequest(
                        0L, "ops:admin", "超管", "F", null, 1, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("系统内置权限不可创建", ex.getReason());
    }

    @Test
    void createPermission_acceptsConcreteSegmentedCode() {
        allowLock(OpsRbacService.opsPermissionCodeLockKey("ops:user:export"));
        when(permissionRepository.findByPermCode("ops:user:export")).thenReturn(Optional.empty());
        when(roleRepository.findByRoleKey("admin")).thenReturn(Optional.empty());

        service.createPermission(ACTOR, new CreateOpsPermissionRequest(
                0L, "ops:user:export", "用户导出", "F", null, 1, null));

        verify(permissionRepository).save(any());
    }

    // ── C14：换绑手机号需短信验证码 ───────────────────────────────────────

    @Test
    void updateMyProfile_phoneChangedWithoutSmsCode_rejected() {
        allowLock(OpsRbacService.opsOperatorLockKey(ACTOR));
        when(userInfoRepository.findByIdForUpdate(ACTOR)).thenReturn(Optional.of(operator(ACTOR, "13900000001")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateMyProfile(ACTOR, new UpdateOpsMeRequest(
                        "13900000002", "新名字", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("换绑手机号需提供短信验证码", ex.getReason());
    }

    @Test
    void updateMyProfile_phoneChangedWithWrongSmsCode_rejected() {
        allowLock(OpsRbacService.opsOperatorLockKey(ACTOR));
        when(userInfoRepository.findByIdForUpdate(ACTOR)).thenReturn(Optional.of(operator(ACTOR, "13900000001")));
        when(smsCodeService.verifyCode("13900000002", "000000")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateMyProfile(ACTOR, new UpdateOpsMeRequest(
                        "13900000002", "新名字", null, null, "000000")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateMyProfile_phoneUnchanged_noSmsRequired() {
        allowLock(OpsRbacService.opsOperatorLockKey(ACTOR));
        UserInfo user = operator(ACTOR, "13900000001");
        when(userInfoRepository.findByIdForUpdate(ACTOR)).thenReturn(Optional.of(user));
        when(userInfoRepository.findById(ACTOR)).thenReturn(Optional.of(user));
        when(userInfoRepository.findByPhoneNumber("13900000001")).thenReturn(Optional.of(user));
        stubOperatorDtoLookups();

        service.updateMyProfile(ACTOR, new UpdateOpsMeRequest(
                "13900000001", "新名字", null, null, null));

        verify(smsCodeService, never()).verifyCode(anyString(), anyString());
        verify(userInfoRepository).save(user);
    }

    @Test
    void updateOperator_phoneChangedWithValidSmsCode_succeeds() {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        UserInfo user = operator(TARGET, "13900000001");
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(user));
        when(smsCodeService.verifyCode("13900000002", "123456")).thenReturn(true);
        when(userInfoRepository.findByPhoneNumber("13900000002")).thenReturn(Optional.empty());
        stubOperatorDtoLookups();

        OpsOperatorDto dto = service.updateOperator(ACTOR, TARGET, new UpdateOpsOperatorRequest(
                "13900000002", "新名字", null, null, null, "123456"));

        assertEquals("新名字", dto.name());
        verify(userInfoRepository).save(user);
    }

    @Test
    void sendPhoneChangeCode_sendsToNewPhoneViaSmsService() {
        doNothing().when(permissionService).requireOperator(ACTOR);

        service.sendPhoneChangeCode(ACTOR, " 13900000002 ");

        verify(smsCodeService).sendCode("13900000002");
    }

    private void stubOperatorDtoLookups() {
        // 不同用例走不同查询路径：lenient 避免严格模式误报
        org.mockito.Mockito.lenient().when(userRoleRepository.findByIdUserId(anyLong())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(userMerchantRepository.findByIdUserId(anyLong())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(userDepartmentRepository.findByUserId(anyLong())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(permissionRepository.findPermCodesByUserId(anyLong())).thenReturn(java.util.Set.of());
    }
}
