package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateMerchantUserRequest;
import com.aicabinet.common.dto.CreateOpsOperatorRequest;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UAT 2026-10-23 P0：operator_user_id_seq 滞后时 nextval 会发出已被占用的 id，
 * 而 BaseTradeMapper.save() 见主键已存在走 updateById ⇒ 「新增账号」静默覆盖在用账号
 * （实测毁掉 3 个种子演示账号）。配对修复是 V398 校准序列 + 分配点改真 insert。
 * 本测试钉的是「不再复发」那一半：撞号必须抛错回滚，且永远不许出现 save()。
 */
@ExtendWith(MockitoExtension.class)
class OperatorCreateNoOverwriteTest {

    private static final Long ACTOR = 100_000_001L;
    private static final String PHONE = "13900000003";
    /** 种子已占用的号（V116 财务演示 = 100000007），模拟序列滞后的发号结果 */
    private static final long TAKEN_ID = 100_000_007L;

    @Mock private OpsRoleMapper roleRepository;
    @Mock private com.aicabinet.trade.mapper.OpsPermissionMapper permissionRepository;
    @Mock private com.aicabinet.trade.mapper.OpsRolePermissionMapper rolePermissionRepository;
    @Mock private OpsUserRoleMapper userRoleRepository;
    @Mock private OpsUserMerchantMapper userMerchantRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private OperatorUserIdAllocator operatorUserIdAllocator;
    @Mock private DistributedLockService distributedLockService;
    @Mock private com.aicabinet.trade.mapper.OpsUserDepartmentMapper userDepartmentRepository;
    @Mock private com.aicabinet.trade.mapper.OpsDepartmentMapper departmentRepository;
    @Mock private AdminAuditService auditService;
    @Mock private com.aicabinet.trade.sms.SmsCodeService smsCodeService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;

    private final OpsRbacService self = mock(OpsRbacService.class);
    private OpsRbacService rbac;
    private MerchantTeamAdminService team;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        rbac = new OpsRbacService(roleRepository, permissionRepository, rolePermissionRepository,
                userRoleRepository, userMerchantRepository, null, permissionService, merchantScopeService,
                userInfoRepository, userAccountRepository, operatorUserIdAllocator, passwordEncoder,
                distributedLockService, null, userDepartmentRepository, departmentRepository, null,
                auditService, smsCodeService, self);
        team = new MerchantTeamAdminService(permissionService, merchantPortalGuard,
                merchantFeaturePackService, userInfoRepository, userAccountRepository,
                userMerchantRepository, userRoleRepository, roleRepository, auditService,
                passwordEncoder, operatorUserIdAllocator, distributedLockService);
    }

    private void allowPhoneLock() {
        org.mockito.Mockito.lenient()
                .when(distributedLockService.tryLock(OpsRbacService.opsOperatorPhoneLockKey(PHONE), 60L, 5L))
                .thenReturn(true);
        org.mockito.Mockito.lenient()
                .when(distributedLockService.tryLock(MerchantTeamAdminService.merchantTeamPhoneLockKey(PHONE), 60L, 5L))
                .thenReturn(true);
    }

    @Test
    void createOperator_usesInsertNeverSave() {
        allowPhoneLock();
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(operatorUserIdAllocator.nextId()).thenReturn(TAKEN_ID);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        rbac.createOperator(ACTOR, new CreateOpsOperatorRequest(
                PHONE, "张三", "ab123456", "ACTIVE", List.of(), null, null));

        ArgumentCaptor<UserInfo> captor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).insert(captor.capture());
        assertEquals(TAKEN_ID, captor.getValue().getUserId());
        verify(userInfoRepository, never()).save(any());
        verify(userAccountRepository, never()).save(any());
        verify(userAccountRepository).insert(any());
    }

    /** 撞号（序列滞后的真实后果）：必须抛错让事务回滚，而不是把已有账号改掉。 */
    @Test
    void createOperator_whenAllocatedIdTaken_throwsAndWritesNoAudit() {
        allowPhoneLock();
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(operatorUserIdAllocator.nextId()).thenReturn(TAKEN_ID);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userInfoRepository.insert(any(UserInfo.class)))
                .thenThrow(new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"user_info_pkey\""));

        assertThrows(DuplicateKeyException.class, () -> rbac.createOperator(ACTOR,
                new CreateOpsOperatorRequest(PHONE, "张三", "ab123456", "ACTIVE", List.of(), null, null)));

        verify(userInfoRepository, never()).save(any());
        verify(userAccountRepository, never()).insert(any());
        verify(auditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    /** 商户团队自助建号走同一个分配器 ⇒ 同一半修复也必须成立。 */
    @Test
    void createTeamUser_usesInsertAndCollidedIdNeverOverwrites() {
        allowPhoneLock();
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(merchantFeaturePackService.allowedMerchantIdsForPack(ACTOR, MerchantFeaturePacks.TEAM))
                .thenReturn(Set.of("MCH-DEFAULT"));
        when(operatorUserIdAllocator.nextId()).thenReturn(TAKEN_ID);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userInfoRepository.insert(any(UserInfo.class)))
                .thenThrow(new DuplicateKeyException("user_info_pkey"));

        // 团队路径的 runWithLock 把非预期异常统一包成 500（不外泄 SQL，但事务照回滚）
        org.springframework.web.server.ResponseStatusException ex = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> team.createTeamUser(ACTOR,
                        new CreateMerchantUserRequest(PHONE, "ab123456", "成员", "merchant_staff")));
        assertEquals(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
        assertEquals(true, ex.getCause() instanceof DuplicateKeyException);

        verify(userInfoRepository, never()).save(any());
        verify(userAccountRepository, never()).save(any());
        verify(userRoleRepository, never()).insert(any());
        verify(auditService, never()).appendLog(eq(ACTOR), eq("MERCHANT_USER_CREATE"),
                anyString(), anyString(), anyString());
    }
}
