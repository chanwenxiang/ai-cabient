package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.CreateOpsOperatorRequest;
import com.aicabinet.common.dto.CreateOpsRoleRequest;
import com.aicabinet.common.dto.ResetOpsOperatorPasswordRequest;
import com.aicabinet.trade.domain.OpsPermission;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.OpsUserMerchant;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-021：角色/权限/分配类变更纳入审计留痕 + 密码复杂度负向验证。
 * 违规密码（纯数字/纯字母）必须被 400 拒绝**且不落审计**（弱密码尝试失败不该污染审计流）。
 */
@ExtendWith(MockitoExtension.class)
class OpsRbacAuditTrailTest {

    private static final Long ACTOR = 100_000_001L;
    private static final Long TARGET = 100_000_002L;

    @Mock private OpsRoleMapper roleRepository;
    @Mock private OpsPermissionMapper permissionRepository;
    @Mock private OpsRolePermissionMapper rolePermissionRepository;
    @Mock private OpsUserRoleMapper userRoleRepository;
    @Mock private OpsUserMerchantMapper userMerchantRepository;
    @Mock private com.aicabinet.trade.mapper.MerchantMapper merchantRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OpsUserDepartmentMapper userDepartmentRepository;
    @Mock private OpsDepartmentMapper departmentRepository;
    @Mock private AdminAuditService auditService;
    @Mock private SmsCodeService smsCodeService;
    @Mock private PasswordEncoder passwordEncoder;

    /** doAssignRolePermissions / assignRoles 成功路径会经 self 代理回读；mock 掉避免 NPE。 */
    private final OpsRbacService self = mock(OpsRbacService.class);

    private OpsRbacService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        service = new OpsRbacService(roleRepository, permissionRepository, rolePermissionRepository,
                userRoleRepository, userMerchantRepository, merchantRepository, permissionService, merchantScopeService,
                userInfoRepository, null, null, passwordEncoder, distributedLockService, null,
                userDepartmentRepository, departmentRepository, null, auditService, smsCodeService, self);
    }

    private void allowLock(String key) {
        when(distributedLockService.tryLock(key, 60L, 5L)).thenReturn(true);
    }

    private static OpsRole role(long roleId, String roleKey) {
        OpsRole role = new OpsRole();
        role.setRoleId(roleId);
        role.setRoleKey(roleKey);
        role.setRoleName("角色" + roleId);
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

    @Test
    void createRole_writesAuditTrail() {
        allowLock(OpsRbacService.opsRoleKeyLockKey("STAFF"));
        when(roleRepository.findByRoleKey("STAFF")).thenReturn(Optional.empty());

        service.createRole(ACTOR, new CreateOpsRoleRequest("STAFF", "门店员工", null, "ACTIVE"));

        verify(auditService).appendLog(eq(ACTOR), eq("OPS_ROLE_CREATE"), eq("ROLE"), any(),
                eq("roleKey=STAFF,roleName=门店员工,status=ACTIVE"));
    }

    @Test
    void assignRolePermissions_writesAuditTrailWithPermCodes() {
        allowLock(OpsRbacService.opsRoleLockKey(5L));
        when(roleRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(role(5L, "CUSTOM")));
        doNothing().when(rolePermissionRepository).deleteByIdRoleId(5L);
        when(permissionRepository.findById(7L)).thenReturn(Optional.of(permission(7L, "ops:task:edit")));

        service.assignRolePermissions(ACTOR, 5L, List.of(7L));

        verify(auditService).appendLog(eq(ACTOR), eq("OPS_ROLE_PERM_ASSIGN"), eq("ROLE"), eq("5"),
                eq("roleKey=CUSTOM,perms=ops:task:edit"));
    }

    @Test
    void resetOperatorPassword_strongPassword_keepsExistingAudit() {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userRoleRepository.findByIdUserId(TARGET)).thenReturn(List.of());
        UserInfo target = operator(TARGET, "13900000002");
        when(userInfoRepository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode("newpass1")).thenReturn("hash");

        service.resetOperatorPassword(ACTOR, TARGET, new ResetOpsOperatorPasswordRequest("newpass1"));

        // 既有审计（重置密码）必须保留
        verify(auditService).appendLog(eq(ACTOR), eq("OPS_OPERATOR_RESET_PASSWORD"),
                eq("USER"), eq(String.valueOf(TARGET)), isNull());
    }

    @Test
    void resetOperatorPassword_digitsOnly_rejectedAndNoAudit() {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userRoleRepository.findByIdUserId(TARGET)).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetOperatorPassword(ACTOR, TARGET, new ResetOpsOperatorPasswordRequest("123456")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("新密码须同时包含字母和数字", ex.getReason());
        verify(auditService, never()).appendLog(any(), any(), any(), any(), any());
    }

    @Test
    void createOperator_digitsOnlyPassword_rejectedAndNoAudit() {
        allowLock(OpsRbacService.opsOperatorPhoneLockKey("13900000003"));
        when(userInfoRepository.findByPhoneNumber("13900000003")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createOperator(ACTOR, new CreateOpsOperatorRequest(
                        "13900000003", "张三", "123456", "ACTIVE", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("新密码须同时包含字母和数字", ex.getReason());
        verify(auditService, never()).appendLog(any(), any(), any(), any(), any());
    }

    // ===== G2-11 §7.17 ⑨（V410）：商户范围授权要能回答「从什么改成什么、这行是谁授的」=====

    private static List<OpsUserMerchant> bindings(long userId, String... merchantIds) {
        return Arrays.stream(merchantIds).map(m -> new OpsUserMerchant(userId, m)).toList();
    }

    private void stubScopeAssignHappyPath(List<OpsUserMerchant> current, String... existingMerchantIds) {
        allowLock(OpsRbacService.opsOperatorLockKey(TARGET));
        when(userInfoRepository.existsById(TARGET)).thenReturn(true);
        when(userMerchantRepository.findByIdUserId(TARGET)).thenReturn(current);
        // null = 全局可见运营员（MerchantScopeService.allowedMerchantIds 的既有语义）
        when(merchantScopeService.allowedMerchantIds(ACTOR)).thenReturn(null);
        for (String id : existingMerchantIds) {
            when(merchantRepository.existsById(id)).thenReturn(true);
        }
    }

    @Test
    void assignMerchants_auditRecordsBeforeAndAfter_andRowCarriesOperator() {
        stubScopeAssignHappyPath(bindings(TARGET, "M002", "M001"), "M003");

        service.assignMerchants(ACTOR, TARGET, List.of("M003"));

        // before 侧必须是 delete 之前读到的那一份：本表整删整插，删完就再也问不出原范围
        verify(auditService).appendLog(eq(ACTOR), eq("OPS_USER_MERCHANT_ASSIGN"), eq("USER"),
                eq(String.valueOf(TARGET)), eq("merchants=M001,M002->M003"));
        ArgumentCaptor<OpsUserMerchant> inserted = ArgumentCaptor.forClass(OpsUserMerchant.class);
        verify(userMerchantRepository).insert(inserted.capture());
        assertEquals(ACTOR, inserted.getValue().getCreatedBy());
    }

    @Test
    void assignMerchants_clearingScope_recordsEmptiedSideAndNoInsert() {
        stubScopeAssignHappyPath(bindings(TARGET, "M001"));

        service.assignMerchants(ACTOR, TARGET, null);

        verify(auditService).appendLog(eq(ACTOR), eq("OPS_USER_MERCHANT_ASSIGN"), eq("USER"),
                eq(String.valueOf(TARGET)), eq("merchants=M001->(空)"));
        verify(userMerchantRepository, never()).insert(any());
    }

    /** 纯格式判据：不起 Spring、不连库（同 AdminDataManageService.changeDetail 的钉法）。 */
    @Test
    void scopeAuditDetail_sortsBothSidesAndNamesEmptySides() {
        assertEquals("merchants=(空)->A,B",
                OpsRbacService.scopeAuditDetail(List.of(), List.of("B", "A")));
    }

    /**
     * admin_audit_log.detail 是 VARCHAR(512) 且 appendLog 静默截断 ⇒ 两侧都必须先各自封顶，
     * 否则「改了 40 个商户」这种授权会把 after 侧整段截没，比只记 after 更糟。
     */
    @Test
    void scopeAuditDetail_capsEachSideSoThePairFitsThe512Column() {
        List<String> wide = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            wide.add("MERCHANT-" + i);
        }

        String detail = OpsRbacService.scopeAuditDetail(wide, wide);

        assertTrue(detail.endsWith("...(截断)"), detail);
        assertTrue(detail.length() <= 512, "detail 长度 " + detail.length());
    }
}
