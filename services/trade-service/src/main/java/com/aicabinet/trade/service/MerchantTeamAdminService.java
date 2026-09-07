package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.CreateMerchantUserRequest;
import com.aicabinet.common.dto.MerchantTeamRoleDto;
import com.aicabinet.common.dto.MerchantUserDto;
import com.aicabinet.common.dto.ResetMerchantUserPasswordRequest;
import com.aicabinet.common.dto.UpdateMerchantUserRequest;
import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 商户门户团队成员 / 角色管理（原 MerchantPortalService team 簇）。
 * Pass 3F G5：角色 ID 硬编码 6–11，须与 DB seed 对齐。
 */
@Service
public class MerchantTeamAdminService {

    private static final String MERCHANT_STORE_MANAGER = "merchant_store_manager";
    private static final String MERCHANT_REPLENISHER = "merchant_replenisher";
    private static final String MERCHANT_USERS_EDIT = "merchant:users:edit";
    private static final String MERCHANT_FINANCE = "merchant_finance";
    private static final String MERCHANT_STAFF = "merchant_staff";
    private static final String MERCHANT_ADMIN = "merchant_admin";
    private static final String MERCHANT = "merchant";
    private static final String LITERAL = "成员不存在";

    /** G5 seed 对齐：商户相关角色主键。 */
    public static final long MERCHANT_ROLE_ID = 6L;
    public static final long MERCHANT_STAFF_ROLE_ID = 7L;
    public static final long MERCHANT_FINANCE_ROLE_ID = 8L;
    public static final long MERCHANT_STORE_MANAGER_ROLE_ID = 10L;
    public static final long MERCHANT_REPLENISHER_ROLE_ID = 11L;

    private static final Set<String> MERCHANT_TEAM_ROLE_KEYS = Set.of(
            MERCHANT, MERCHANT_ADMIN, MERCHANT_STAFF, MERCHANT_FINANCE,
            MERCHANT_STORE_MANAGER, MERCHANT_REPLENISHER
    );

    private final PermissionService permissionService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final OpsUserMerchantMapper userMerchantRepository;
    private final OpsUserRoleMapper userRoleRepository;
    private final OpsRoleMapper roleRepository;
    private final AdminAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final OperatorUserIdAllocator operatorUserIdAllocator;
    private final DistributedLockService distributedLockService;

    public MerchantTeamAdminService(PermissionService permissionService,
                                    MerchantPortalGuard merchantPortalGuard,
                                    MerchantFeaturePackService merchantFeaturePackService,
                                    UserInfoMapper userInfoRepository,
                                    UserAccountMapper userAccountRepository,
                                    OpsUserMerchantMapper userMerchantRepository,
                                    OpsUserRoleMapper userRoleRepository,
                                    OpsRoleMapper roleRepository,
                                    AdminAuditService auditService,
                                    PasswordEncoder passwordEncoder,
                                    OperatorUserIdAllocator operatorUserIdAllocator,
                                    DistributedLockService distributedLockService) {
        this.permissionService = permissionService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.userMerchantRepository = userMerchantRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.operatorUserIdAllocator = operatorUserIdAllocator;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<MerchantUserDto> listTeamUsers(Long userId) {
        permissionService.requirePermission(userId, "merchant:users:list");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.TEAM);
        if (merchants == null || merchants.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = userMerchantRepository.findByMerchantIdIn(merchants).stream()
                .map(m -> m.getId().getUserId())
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userInfoRepository.findByUserIdIn(new ArrayList<>(userIds)).stream()
                .sorted(Comparator.comparing(UserInfo::getUserId))
                .map(u -> toMerchantUserDto(u, u.getUserId().equals(userId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantTeamRoleDto> listTeamRoles(Long userId) {
        permissionService.requireAnyPermission(userId, "merchant:users:invite", MERCHANT_USERS_EDIT);
        merchantPortalGuard.requireAccess(userId);
        return List.of(
                new MerchantTeamRoleDto(MERCHANT, "商户管理员", "全量经营与团队管理"),
                new MerchantTeamRoleDto(MERCHANT_STORE_MANAGER, "店长", "现场+经营只读，可看团队"),
                new MerchantTeamRoleDto(MERCHANT_FINANCE, "财务", "结算对账与钱包只读"),
                new MerchantTeamRoleDto(MERCHANT_REPLENISHER, "补货员", "柜机补货与库存"),
                new MerchantTeamRoleDto(MERCHANT_STAFF, "店员", "通用只读协同")
        );
    }

    @Transactional
    public MerchantUserDto updateTeamUser(Long operatorId, Long targetUserId, UpdateMerchantUserRequest request) {
        permissionService.requirePermission(operatorId, MERCHANT_USERS_EDIT);
        merchantPortalGuard.requireAccess(operatorId);
        return runWithTeamUserLock(targetUserId, () -> doUpdateTeamUser(operatorId, targetUserId, request));
    }

    private MerchantUserDto doUpdateTeamUser(Long operatorId, Long targetUserId, UpdateMerchantUserRequest request) {
        UserInfo target = userInfoRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        assertTeamMemberAccess(operatorId, targetUserId);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            target.setName(request.displayName().trim());
        }
        if (request.roleKey() != null && !request.roleKey().isBlank()) {
            if (targetUserId.equals(operatorId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改自己的角色");
            }
            long roleId = resolveMerchantRoleId(request.roleKey());
            userRoleRepository.deleteByIdUserId(targetUserId);
            userRoleRepository.insert(new OpsUserRole(targetUserId, roleId));
        }
        userInfoRepository.save(target);
        auditService.appendLog(operatorId, "MERCHANT_USER_UPDATE", "USER", String.valueOf(targetUserId),
                "role=" + request.roleKey() + ",name=" + request.displayName());
        return toMerchantUserDto(target, false);
    }

    @Transactional
    public MerchantUserDto disableTeamUser(Long operatorId, Long targetUserId) {
        permissionService.requirePermission(operatorId, "merchant:users:disable");
        merchantPortalGuard.requireAccess(operatorId);
        if (targetUserId.equals(operatorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用自己");
        }
        return runWithTeamUserLock(targetUserId, () -> doDisableTeamUser(operatorId, targetUserId));
    }

    private MerchantUserDto doDisableTeamUser(Long operatorId, Long targetUserId) {
        UserInfo target = userInfoRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        assertTeamMemberAccess(operatorId, targetUserId);
        target.setStatus("INACTIVE");
        userInfoRepository.save(target);
        auditService.appendLog(operatorId, "MERCHANT_USER_DISABLE", "USER", String.valueOf(targetUserId), null);
        return toMerchantUserDto(target, false);
    }

    @Transactional
    public MerchantUserDto enableTeamUser(Long operatorId, Long targetUserId) {
        permissionService.requirePermission(operatorId, MERCHANT_USERS_EDIT);
        merchantPortalGuard.requireAccess(operatorId);
        return runWithTeamUserLock(targetUserId, () -> doEnableTeamUser(operatorId, targetUserId));
    }

    private MerchantUserDto doEnableTeamUser(Long operatorId, Long targetUserId) {
        UserInfo target = userInfoRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        assertTeamMemberAccess(operatorId, targetUserId);
        target.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        userInfoRepository.save(target);
        auditService.appendLog(operatorId, "MERCHANT_USER_ENABLE", "USER", String.valueOf(targetUserId), null);
        return toMerchantUserDto(target, false);
    }

    @Transactional
    public MerchantUserDto resetTeamUserPassword(Long operatorId, Long targetUserId,
                                                 ResetMerchantUserPasswordRequest request) {
        permissionService.requirePermission(operatorId, "merchant:users:reset-password");
        merchantPortalGuard.requireAccess(operatorId);
        if (request == null || request.password() == null || request.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        return runWithTeamUserLock(targetUserId, () -> doResetTeamUserPassword(operatorId, targetUserId, request));
    }

    private MerchantUserDto doResetTeamUserPassword(Long operatorId, Long targetUserId,
                                                    ResetMerchantUserPasswordRequest request) {
        UserInfo target = userInfoRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        assertTeamMemberAccess(operatorId, targetUserId);
        target.setPasswordHash(passwordEncoder.encode(request.password()));
        userInfoRepository.save(target);
        auditService.appendLog(operatorId, "MERCHANT_USER_RESET_PASSWORD", "USER", String.valueOf(targetUserId), null);
        return toMerchantUserDto(target, targetUserId.equals(operatorId));
    }

    @Transactional
    public MerchantUserDto createTeamUser(Long userId, CreateMerchantUserRequest request) {
        permissionService.requirePermission(userId, "merchant:users:invite");
        merchantPortalGuard.requireAccess(userId);
        if (request.phoneNumber() == null || request.phoneNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        String phone = request.phoneNumber().trim();
        return runWithTeamPhoneLock(phone, () -> doCreateTeamUser(userId, request, phone));
    }

    private MerchantUserDto doCreateTeamUser(Long userId, CreateMerchantUserRequest request, String phone) {
        if (userInfoRepository.findByPhoneNumber(phone).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已注册");
        }
        Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.TEAM);
        if (merchants == null || merchants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
        long newUserId = operatorUserIdAllocator.nextId();

        UserInfo user = new UserInfo();
        user.setUserId(newUserId);
        user.setAccountType(CabinetConstants.ACCOUNT_TYPE_OPERATOR);
        user.setPhoneNumber(phone);
        user.setName(request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim() : "商户成员");
        user.setVerified(true);
        user.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userInfoRepository.save(user);

        UserAccount account = new UserAccount();
        account.setUserId(newUserId);
        account.setBalanceCents(0);
        userAccountRepository.save(account);

        long roleId = resolveMerchantRoleId(request.roleKey());
        userRoleRepository.insert(new OpsUserRole(newUserId, roleId));
        for (String merchantId : merchants) {
            userMerchantRepository.insert(new OpsUserMerchant(newUserId, merchantId));
        }
        auditService.appendLog(userId, "MERCHANT_USER_CREATE", "USER", String.valueOf(newUserId),
                "phone=" + phone + ",role=" + request.roleKey());
        return toMerchantUserDto(user, false);
    }

    private void assertTeamMemberAccess(Long operatorId, Long targetUserId) {
        Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(
                operatorId, MerchantFeaturePacks.TEAM);
        if (merchants == null || merchants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
        Set<String> targetMerchants = userMerchantRepository.findByIdUserId(targetUserId).stream()
                .map(m -> m.getId().getMerchantId())
                .collect(Collectors.toSet());
        boolean overlap = targetMerchants.stream().anyMatch(merchants::contains);
        if (!overlap) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权管理该成员");
        }
    }

    private MerchantUserDto toMerchantUserDto(UserInfo user, boolean self) {
        String roleKey = resolveMerchantRoleKey(user.getUserId());
        String roleName = switch (roleKey) {
            case MERCHANT -> "商户管理员";
            case MERCHANT_STORE_MANAGER -> "店长";
            case MERCHANT_FINANCE -> "财务";
            case MERCHANT_REPLENISHER -> "补货员";
            case MERCHANT_STAFF -> "店员";
            default -> roleKey;
        };
        return new MerchantUserDto(
                user.getUserId(),
                user.getPhoneNumber(),
                user.getName(),
                roleKey,
                roleName,
                user.getStatus() == null ? CabinetConstants.PROMOTION_STATUS_ACTIVE : user.getStatus(),
                self
        );
    }

    /** G5：硬编码 roleId 6–11，与 seed 对齐；变更须同步迁移。 */
    public long resolveMerchantRoleId(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return MERCHANT_STAFF_ROLE_ID;
        }
        String key = roleKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case MERCHANT, MERCHANT_ADMIN -> MERCHANT_ROLE_ID;
            case MERCHANT_FINANCE -> MERCHANT_FINANCE_ROLE_ID;
            case MERCHANT_STORE_MANAGER -> MERCHANT_STORE_MANAGER_ROLE_ID;
            case MERCHANT_REPLENISHER -> MERCHANT_REPLENISHER_ROLE_ID;
            case MERCHANT_STAFF -> MERCHANT_STAFF_ROLE_ID;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的角色: " + roleKey);
        };
    }

    private String resolveMerchantRoleKey(Long userId) {
        return userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()))
                .flatMap(Optional::stream)
                .map(OpsRole::getRoleKey)
                .filter(MERCHANT_TEAM_ROLE_KEYS::contains)
                .map(key -> MERCHANT_ADMIN.equals(key) ? MERCHANT : key)
                .findFirst()
                .orElse(MERCHANT_STAFF);
    }

    /** 邀请成员手机号锁（门面/并发测试兼容入口）。 */
    public static String merchantTeamPhoneLockKey(String phone) {
        return "merchant:team-phone:" + phone;
    }

    private <T> T runWithTeamUserLock(long targetUserId, Supplier<T> action) {
        return runWithLock(AccountService.userAccountLockKey(targetUserId), "成员处理中，请稍后重试", action);
    }

    private <T> T runWithTeamPhoneLock(String phone, Supplier<T> action) {
        return runWithLock(merchantTeamPhoneLockKey(phone), "成员邀请处理中，请稍后重试", action);
    }

    private <T> T runWithLock(String lockKey, String busyMessage, Supplier<T> action) {
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, busyMessage);
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

}
