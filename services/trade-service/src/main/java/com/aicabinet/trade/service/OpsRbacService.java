package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.OpsPermission;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.OpsRolePermission;
import com.aicabinet.trade.domain.OpsDepartment;
import com.aicabinet.trade.domain.OpsUserDepartment;
import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsDepartmentMapper;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsRolePermissionMapper;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserDepartmentMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class OpsRbacService {
    private static final String PERM_OPS_RBAC_ASSIGN = "ops:rbac:assign";
    private static final String PERM_OPS_RBAC_ROLE = "ops:rbac:role";
    private static final String INACTIVE = "INACTIVE";
    private static final String ADMIN = "admin";


    private final OpsRoleMapper roleRepository;
    private final OpsPermissionMapper permissionRepository;
    private final OpsRolePermissionMapper rolePermissionRepository;
    private final OpsUserRoleMapper userRoleRepository;
    private final OpsUserMerchantMapper userMerchantRepository;
    private final MerchantMapper merchantRepository;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final OperatorUserIdAllocator operatorUserIdAllocator;
    private final PasswordEncoder passwordEncoder;
    private final DistributedLockService distributedLockService;
    private final DepartmentService departmentService;
    private final OpsUserDepartmentMapper userDepartmentRepository;
    private final OpsDepartmentMapper departmentRepository;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final OpsRbacService self;

    public OpsRbacService(OpsRoleMapper roleRepository,
                          OpsPermissionMapper permissionRepository,
                          OpsRolePermissionMapper rolePermissionRepository,
                          OpsUserRoleMapper userRoleRepository,
                          OpsUserMerchantMapper userMerchantRepository,
                          MerchantMapper merchantRepository,
                          PermissionService permissionService,
                          MerchantScopeService merchantScopeService,
                          UserInfoMapper userInfoRepository,
                          UserAccountMapper userAccountRepository,
                          OperatorUserIdAllocator operatorUserIdAllocator,
                          PasswordEncoder passwordEncoder,
                          DistributedLockService distributedLockService,
                          DepartmentService departmentService,
                          OpsUserDepartmentMapper userDepartmentRepository,
                          OpsDepartmentMapper departmentRepository, @Lazy OpsRbacService self) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.userMerchantRepository = userMerchantRepository;
        this.merchantRepository = merchantRepository;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.operatorUserIdAllocator = operatorUserIdAllocator;
        this.passwordEncoder = passwordEncoder;
        this.distributedLockService = distributedLockService;
        this.departmentService = departmentService;
        this.userDepartmentRepository = userDepartmentRepository;
        this.departmentRepository = departmentRepository;
        this.self = self;
    }

    @Transactional
    public OpsRoleDto createRole(Long operatorId, CreateOpsRoleRequest request) {
        permissionService.requirePermission(operatorId, "ops:rbac:role:add");
        String roleKey = request.roleKey().trim();
        return runWithRoleKeyLock(roleKey, () -> doCreateRole(request, roleKey));
    }

    private OpsRoleDto doCreateRole(CreateOpsRoleRequest request, String roleKey) {
        if (roleRepository.findByRoleKey(roleKey).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色标识已存在");
        }
        if (ADMIN.equalsIgnoreCase(roleKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不允许创建 admin 角色");
        }
        OpsRole role = new OpsRole();
        role.setRoleKey(roleKey);
        role.setRoleName(request.roleName().trim());
        role.setRemark(request.remark());
        role.setStatus(normalizeRoleStatus(request.status()));
        roleRepository.save(role);
        return toRoleDto(role, 0);
    }

    @Transactional
    public OpsRoleDto updateRole(Long operatorId, Long roleId, UpdateOpsRoleRequest request) {
        permissionService.requirePermission(operatorId, "ops:rbac:role:edit");
        return runWithRoleLock(roleId, () -> doUpdateRole(roleId, request));
    }

    private OpsRoleDto doUpdateRole(Long roleId, UpdateOpsRoleRequest request) {
        OpsRole role = roleRepository.findByIdForUpdate(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ROLE_NOT_FOUND));
        if (ADMIN.equals(role.getRoleKey()) && request.status() != null
                && !CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(request.status().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用超级管理员角色");
        }
        role.setRoleName(request.roleName().trim());
        role.setRemark(request.remark());
        if (request.status() != null && !request.status().isBlank()) {
            role.setStatus(normalizeRoleStatus(request.status()));
        }
        roleRepository.save(role);
        int permCount = rolePermissionRepository.findPermissionIdsByRoleId(roleId).size();
        return toRoleDto(role, permCount);
    }

    @Transactional(readOnly = true)
    public List<OpsRoleDto> listRoles(Long operatorId) {
        // 角色页维护 / 运营账号分配角色 均可读取角色列表
        permissionService.requireAnyPermission(operatorId, PERM_OPS_RBAC_ROLE, PERM_OPS_RBAC_ASSIGN);
        return roleRepository.findAll().stream()
                .map(r -> {
                    int permCount = rolePermissionRepository.findPermissionIdsByRoleId(r.getRoleId()).size();
                    return toRoleDto(r, permCount);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OpsPermissionDto> listPermissions(Long operatorId, boolean includeInactive) {
        // 角色分配权限 / 菜单管理页 均可读取权限树
        permissionService.requireAnyPermission(operatorId, PERM_OPS_RBAC_ROLE, "ops:rbac:menu");
        List<OpsPermission> rows = includeInactive
                ? permissionRepository.findAllOrderBySortOrderAsc()
                : permissionRepository.findByStatusOrderBySortOrderAsc(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        return rows.stream().map(this::toPermissionDto).toList();
    }

    @Transactional
    public OpsPermissionDto createPermission(Long operatorId, CreateOpsPermissionRequest request) {
        permissionService.requirePermission(operatorId, "ops:rbac:menu:add");
        String permCode = request.permCode().trim();
        return runWithPermissionCodeLock(permCode, () -> doCreatePermission(request, permCode));
    }

    private OpsPermissionDto doCreatePermission(CreateOpsPermissionRequest request, String permCode) {
        String permType = normalizePermType(request.permType());
        if (permissionRepository.findByPermCode(permCode).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "权限标识已存在");
        }
        Long parentId = request.parentId() == null ? 0L : request.parentId();
        ensureParentExists(parentId);
        OpsPermission p = new OpsPermission();
        p.setParentId(parentId);
        p.setPermCode(permCode);
        p.setPermName(request.permName().trim());
        p.setPermType(permType);
        p.setPath(blankToNull(request.path()));
        p.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        p.setStatus(normalizeStatus(request.status()));
        permissionRepository.save(p);
        // 新建默认挂到超级管理员（复合主键实体用 insert，避免 save→selectById）
        roleRepository.findByRoleKey(ADMIN).ifPresent(admin ->
                rolePermissionRepository.insert(new OpsRolePermission(admin.getRoleId(), p.getPermissionId())));
        return toPermissionDto(p);
    }

    @Transactional
    public OpsPermissionDto updatePermission(Long operatorId, Long permissionId, UpdateOpsPermissionRequest request) {
        permissionService.requirePermission(operatorId, "ops:rbac:menu:edit");
        return runWithPermissionLock(permissionId, () -> doUpdatePermission(permissionId, request));
    }

    private OpsPermissionDto doUpdatePermission(Long permissionId, UpdateOpsPermissionRequest request) {
        OpsPermission p = permissionRepository.findByIdForUpdate(permissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "菜单权限不存在"));
        Long parentId = request.parentId() == null ? p.getParentId() : request.parentId();
        if (parentId.equals(permissionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父级不能是自身");
        }
        ensureParentExists(parentId);
        p.setParentId(parentId);
        p.setPermName(request.permName().trim());
        p.setPermType(normalizePermType(request.permType()));
        p.setPath(blankToNull(request.path()));
        if (request.sortOrder() != null) {
            p.setSortOrder(request.sortOrder());
        }
        if (request.status() != null && !request.status().isBlank()) {
            p.setStatus(normalizeStatus(request.status()));
        }
        permissionRepository.save(p);
        return toPermissionDto(p);
    }

    @Transactional
    public void deletePermission(Long operatorId, Long permissionId) {
        permissionService.requirePermission(operatorId, "ops:rbac:menu:remove");
        runWithPermissionLock(permissionId, () -> {
            doDeletePermission(permissionId);
            return null;
        });
    }

    private void doDeletePermission(Long permissionId) {
        OpsPermission p = permissionRepository.findByIdForUpdate(permissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "菜单权限不存在"));
        long childCount = permissionRepository.countByParentIdAndStatus(permissionId, CabinetConstants.PROMOTION_STATUS_ACTIVE);
        if (childCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先删除或停用子菜单");
        }
        if ("ops:admin".equals(p.getPermCode()) || "ops".equals(p.getPermCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "系统内置权限不可删除");
        }
        p.setStatus(INACTIVE);
        permissionRepository.save(p);
    }

    private OpsPermissionDto toPermissionDto(OpsPermission p) {
        return new OpsPermissionDto(
                p.getPermissionId(), p.getParentId(), p.getPermCode(),
                p.getPermName(), p.getPermType(), p.getPath(), p.getSortOrder(), p.getStatus());
    }

    private void ensureParentExists(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (!permissionRepository.existsById(parentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父级菜单不存在");
        }
    }

    private static String normalizePermType(String permType) {
        if (permType == null || permType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限类型不能为空");
        }
        String t = permType.trim().toUpperCase();
        if (!"M".equals(t) && !"C".equals(t) && !"F".equals(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限类型仅支持 M/C/F");
        }
        return t;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return CabinetConstants.PROMOTION_STATUS_ACTIVE;
        }
        String s = status.trim().toUpperCase();
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(s) && !INACTIVE.equals(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态仅支持 ACTIVE 或 INACTIVE");
        }
        return s;
    }

    private static String blankToNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return path.trim();
    }

    @Transactional(readOnly = true)
    public OpsRolePermissionsDto getRolePermissions(Long operatorId, Long roleId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_RBAC_ROLE, "ops:rbac:role:perm");
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ROLE_NOT_FOUND));
        List<Long> permissionIds = rolePermissionRepository.findPermissionIdsByRoleId(roleId);
        return new OpsRolePermissionsDto(role.getRoleId(), role.getRoleKey(), role.getRoleName(), permissionIds);
    }

    @Transactional
    public OpsRolePermissionsDto assignRolePermissions(Long operatorId, Long roleId, List<Long> permissionIds) {
        permissionService.requirePermission(operatorId, "ops:rbac:role:perm");
        return runWithRoleLock(roleId, () -> doAssignRolePermissions(operatorId, roleId, permissionIds));
    }

    private OpsRolePermissionsDto doAssignRolePermissions(Long operatorId, Long roleId, List<Long> permissionIds) {
        var role = roleRepository.findByIdForUpdate(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ROLE_NOT_FOUND));
        if (ADMIN.equals(role.getRoleKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_MODIFY_ADMIN_ROLE);
        }
        rolePermissionRepository.deleteByIdRoleId(roleId);
        if (permissionIds != null) {
            for (Long permissionId : permissionIds) {
                if (!permissionRepository.existsById(permissionId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ApiMessages.INVALID_REQUEST + "：permissionId=" + permissionId);
                }
                rolePermissionRepository.insert(new OpsRolePermission(roleId, permissionId));
            }
        }
        return self.getRolePermissions(operatorId, roleId);
    }

    @Transactional(readOnly = true)
    public PageResult<OpsOperatorDto> listOperators(Long operatorId, int page, int size, String phone) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_RBAC_ASSIGN, "ops:replenishment:edit");
        var pageable = PageRequest.of(page, Math.min(size, 100));
        Page<UserInfo> users = (phone == null || phone.isBlank())
                ? userInfoRepository.findByUserIdGreaterThanEqualOrderByUserIdDesc(
                        CabinetConstants.OPERATOR_USER_ID_START, pageable)
                : userInfoRepository.findByUserIdGreaterThanEqualAndPhoneNumberContainingOrderByUserIdDesc(
                        CabinetConstants.OPERATOR_USER_ID_START, phone.trim(), pageable);
        List<OpsOperatorDto> items = users.getContent().stream()
                .map(this::toOperatorDto)
                .toList();
        return new PageResult<>(items, users.getNumber(), users.getSize(), users.getTotalElements());
    }

    @Transactional
    public OpsOperatorDto createOperator(Long operatorId, CreateOpsOperatorRequest request) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign:add");
        String phone = normalizePhone(request.phoneNumber());
        return runWithOperatorPhoneLock(phone, () -> doCreateOperator(request, phone));
    }

    private OpsOperatorDto doCreateOperator(CreateOpsOperatorRequest request, String phone) {
        if (userInfoRepository.findByPhoneNumber(phone).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.PHONE_ALREADY_EXISTS);
        }
        long newUserId = operatorUserIdAllocator.nextId();
        UserInfo user = new UserInfo();
        user.setUserId(newUserId);
        user.setPhoneNumber(phone);
        user.setName(request.name().trim());
        user.setVerified(true);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(normalizeAccountStatus(request.status()));
        userInfoRepository.save(user);

        UserAccount account = new UserAccount();
        account.setUserId(newUserId);
        account.setBalanceCents(0);
        userAccountRepository.save(account);

        if (request.roleIds() != null && !request.roleIds().isEmpty()) {
            replaceUserRoles(newUserId, request.roleIds());
        }
        if (request.deptIds() != null || request.primaryDeptId() != null) {
            departmentService.replaceUserDepartments(newUserId, request.deptIds(), request.primaryDeptId());
        }
        return toOperatorDto(user);
    }

    @Transactional
    public OpsOperatorDto updateOperator(Long operatorId, Long userId, UpdateOpsOperatorRequest request) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign:edit");
        return runWithOperatorLock(userId, () -> doUpdateOperator(operatorId, userId, request));
    }

    private OpsOperatorDto doUpdateOperator(Long operatorId, Long userId, UpdateOpsOperatorRequest request) {
        ensureOperatorAccount(userId);
        UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        String phone = normalizePhone(request.phoneNumber());
        userInfoRepository.findByPhoneNumber(phone).ifPresent(existing -> {
            if (!existing.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.PHONE_ALREADY_EXISTS);
            }
        });
        user.setPhoneNumber(phone);
        user.setName(request.name().trim());
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.status() != null && !request.status().isBlank()) {
            String status = normalizeAccountStatus(request.status());
            if (INACTIVE.equals(status) && userId.equals(operatorId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_DISABLE_SELF);
            }
            user.setStatus(status);
        }
        userInfoRepository.save(user);
        if (request.deptIds() != null || request.primaryDeptId() != null) {
            departmentService.replaceUserDepartments(userId, request.deptIds(), request.primaryDeptId());
        }
        return toOperatorDto(user);
    }

    @Transactional
    public void disableOperator(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign:disable");
        runWithOperatorLock(userId, () -> {
            doDisableOperator(operatorId, userId);
            return null;
        });
    }

    private void doDisableOperator(Long operatorId, Long userId) {
        ensureOperatorAccount(userId);
        if (userId.equals(operatorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_DISABLE_SELF);
        }
        UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        user.setStatus(INACTIVE);
        userInfoRepository.save(user);
    }

    /** 运营账号自助修改密码（个人中心）。 */
    @Transactional
    public void changeMyPassword(Long operatorId, ChangePasswordRequest request) {
        runWithOperatorLock(operatorId, () -> {
            doChangeMyPassword(operatorId, request);
            return null;
        });
    }

    private void doChangeMyPassword(Long operatorId, ChangePasswordRequest request) {
        String newPassword = request.newPassword();
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度需在 6-64 位之间");
        }
        UserInfo user = userInfoRepository.findByIdForUpdate(operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank() || !passwordEncoder.matches(request.oldPassword(), hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userInfoRepository.save(user);
    }

    @Transactional(readOnly = true)
    public OpsUserRolesDto getUserRoles(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, PERM_OPS_RBAC_ASSIGN);
        ensureOperatorAccount(userId);
        List<Long> roleIds = userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> ur.getId().getRoleId())
                .toList();
        Set<String> perms = permissionRepository.findPermCodesByUserId(userId);
        return new OpsUserRolesDto(userId, roleIds, perms.stream().toList());
    }

    @Transactional
    public OpsUserRolesDto assignRoles(Long operatorId, Long userId, List<Long> roleIds) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign:role");
        return runWithOperatorLock(userId, () -> {
            ensureOperatorAccount(userId);
            replaceUserRoles(userId, roleIds);
            return self.getUserRoles(operatorId, userId);
        });
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        userRoleRepository.deleteByIdUserId(userId);
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            if (!roleRepository.existsById(roleId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ApiMessages.INVALID_REQUEST + "：roleId=" + roleId);
            }
            userRoleRepository.insert(new OpsUserRole(userId, roleId));
        }
    }

    @Transactional(readOnly = true)
    public OpsUserMerchantsDto getUserMerchants(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, PERM_OPS_RBAC_ASSIGN);
        ensureOperatorAccount(userId);
        List<String> merchantIds = userMerchantRepository.findByIdUserId(userId).stream()
                .map(m -> m.getId().getMerchantId())
                .toList();
        return new OpsUserMerchantsDto(userId, merchantIds);
    }

    @Transactional
    public OpsUserMerchantsDto assignMerchants(Long operatorId, Long userId, List<String> merchantIds) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign:merchant");
        return runWithOperatorLock(userId, () -> doAssignMerchants(operatorId, userId, merchantIds));
    }

    private OpsUserMerchantsDto doAssignMerchants(Long operatorId, Long userId, List<String> merchantIds) {
        ensureOperatorAccount(userId);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        userMerchantRepository.deleteByIdUserId(userId);
        if (merchantIds != null) {
            for (String merchantId : merchantIds) {
                if (merchantId == null || merchantId.isBlank()) {
                    continue;
                }
                String id = merchantId.trim();
                if (!merchantRepository.existsById(id)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ApiMessages.INVALID_REQUEST + "：merchantId=" + id);
                }
                if (allowed != null && !allowed.contains(id)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
                }
                userMerchantRepository.insert(new OpsUserMerchant(userId, id));
            }
        }
        return self.getUserMerchants(operatorId, userId);
    }

    @Transactional(readOnly = true)
    public Set<String> myPermissions(Long operatorId) {
        permissionService.requireOperator(operatorId);
        return permissionService.listPermCodes(operatorId);
    }

    /**
     * 系统中状态为 ACTIVE 的菜单/目录权限码，用于侧栏与路由门禁。
     * 停用菜单后即使持有 ops:admin 也不应再出现在导航中。
     */
    @Transactional(readOnly = true)
    public Set<String> activeNavPermissions(Long operatorId) {
        permissionService.requireOperator(operatorId);
        return permissionRepository.findByStatusOrderBySortOrderAsc(CabinetConstants.PROMOTION_STATUS_ACTIVE).stream()
                .filter(p -> {
                    String t = p.getPermType();
                    return "C".equals(t) || "M".equals(t);
                })
                .map(OpsPermission::getPermCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @Transactional(readOnly = true)
    public OpsMeDto myProfile(Long operatorId) {
        permissionService.requireOperator(operatorId);
        UserInfo user = userInfoRepository.findById(operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        List<Long> roleIds = userRoleRepository.findByIdUserId(operatorId).stream()
                .map(ur -> ur.getId().getRoleId())
                .toList();
        List<String> roleNames = roleIds.stream()
                .flatMap(id -> roleRepository.findById(id).stream())
                .map(OpsRole::getRoleName)
                .toList();
        int permCount = permissionRepository.findPermCodesByUserId(operatorId).size();
        boolean global = merchantScopeService.isGlobalScope(operatorId);
        List<String> merchantIds = global
                ? List.of()
                : userMerchantRepository.findByIdUserId(operatorId).stream()
                        .map(m -> m.getId().getMerchantId())
                        .toList();
        List<String> merchantNames = resolveMerchantNames(merchantIds);
        return new OpsMeDto(
                operatorId,
                user.getPhoneNumber(),
                user.getName(),
                user.getEmail(),
                user.getAvatarUrl(),
                roleNames,
                permCount,
                global,
                merchantIds,
                merchantNames
        );
    }

    /** 运营账号自助更新个人资料（姓名 / 手机 / 邮箱 / 头像）。 */
    @Transactional
    public OpsMeDto updateMyProfile(Long operatorId, UpdateOpsMeRequest request) {
        permissionService.requireOperator(operatorId);
        String phone = normalizePhone(request.phoneNumber());
        return runWithOperatorLock(operatorId, () -> doUpdateMyProfile(operatorId, request, phone));
    }

    private OpsMeDto doUpdateMyProfile(Long operatorId, UpdateOpsMeRequest request, String phone) {
        UserInfo user = userInfoRepository.findByIdForUpdate(operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        userInfoRepository.findByPhoneNumber(phone).ifPresent(existing -> {
            if (!existing.getUserId().equals(operatorId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.PHONE_ALREADY_EXISTS);
            }
        });
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "姓名不能为空");
        }
        String email = blankToNull(request.email());
        String avatarUrl = blankToNull(request.avatarUrl());
        if (avatarUrl != null && avatarUrl.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像地址过长");
        }
        user.setPhoneNumber(phone);
        user.setName(name);
        user.setEmail(email);
        user.setAvatarUrl(avatarUrl);
        userInfoRepository.save(user);
        return myProfile(operatorId);
    }

    private OpsOperatorDto toOperatorDto(UserInfo user) {
        List<Long> roleIds = userRoleRepository.findByIdUserId(user.getUserId()).stream()
                .map(ur -> ur.getId().getRoleId())
                .toList();
        List<String> roleNames = roleIds.stream()
                .flatMap(id -> roleRepository.findById(id).stream())
                .map(OpsRole::getRoleName)
                .toList();
        List<String> merchantIds = userMerchantRepository.findByIdUserId(user.getUserId()).stream()
                .map(m -> m.getId().getMerchantId())
                .toList();
        List<String> merchantNames = resolveMerchantNames(merchantIds);
        List<OpsUserDepartment> deptLinks = userDepartmentRepository.findByUserId(user.getUserId());
        List<Long> deptIds = deptLinks.stream().map(OpsUserDepartment::getDeptId).toList();
        List<String> deptNames = deptIds.stream()
                .map(id -> departmentRepository.findById(id)
                        .map(OpsDepartment::getDeptName)
                        .orElse(String.valueOf(id)))
                .toList();
        Long primaryDeptId = deptLinks.stream()
                .filter(ud -> Boolean.TRUE.equals(ud.getIsPrimary()))
                .map(OpsUserDepartment::getDeptId)
                .findFirst()
                .orElse(null);
        String primaryDeptName = primaryDeptId == null ? null
                : departmentRepository.findById(primaryDeptId).map(OpsDepartment::getDeptName).orElse(null);
        return new OpsOperatorDto(
                user.getUserId(),
                user.getPhoneNumber(),
                user.getName(),
                user.getStatus() == null || user.getStatus().isBlank() ? CabinetConstants.PROMOTION_STATUS_ACTIVE : user.getStatus(),
                roleNames,
                roleIds,
                merchantIds,
                merchantNames,
                deptIds,
                deptNames,
                primaryDeptId,
                primaryDeptName
        );
    }

    private List<String> resolveMerchantNames(List<String> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        return merchantIds.stream()
                .map(id -> merchantRepository.findById(id)
                        .map(m -> m.getMerchantName() == null || m.getMerchantName().isBlank()
                                ? id
                                : m.getMerchantName())
                        .orElse(id))
                .toList();
    }

    private void ensureOperatorAccount(Long userId) {
        if (userId == null || userId < CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.NOT_OPERATOR_ACCOUNT);
        }
        if (!userInfoRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND);
        }
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
        }
        String normalized = phone.trim();
        if (!normalized.matches("1\\d{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_PHONE);
        }
        return normalized;
    }

    private static String normalizeAccountStatus(String status) {
        if (status == null || status.isBlank()) {
            return CabinetConstants.PROMOTION_STATUS_ACTIVE;
        }
        String normalized = status.trim().toUpperCase();
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(normalized) && !INACTIVE.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号状态仅支持 ACTIVE 或 INACTIVE");
        }
        return normalized;
    }

    private static String normalizeRoleStatus(String status) {
        if (status == null || status.isBlank()) {
            return CabinetConstants.PROMOTION_STATUS_ACTIVE;
        }
        String normalized = status.trim().toUpperCase();
        if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(normalized) && !INACTIVE.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色状态仅支持 ACTIVE 或 INACTIVE");
        }
        return normalized;
    }

    private static OpsRoleDto toRoleDto(OpsRole role, int permCount) {
        return new OpsRoleDto(
                role.getRoleId(), role.getRoleKey(), role.getRoleName(),
                role.getStatus(), role.getRemark(), List.of(permCount + " 项权限"));
    }

    static String opsRoleLockKey(Long roleId) {
        return "ops:role:" + roleId;
    }

    static String opsRoleKeyLockKey(String roleKey) {
        return "ops:role:key:" + roleKey.trim().toLowerCase();
    }

    static String opsPermissionLockKey(Long permissionId) {
        return "ops:permission:" + permissionId;
    }

    static String opsPermissionCodeLockKey(String permCode) {
        return "ops:permission:code:" + permCode.trim();
    }

    static String opsOperatorLockKey(Long userId) {
        return "ops:operator:" + userId;
    }

    static String opsOperatorPhoneLockKey(String phone) {
        return "ops:operator:phone:" + phone.trim();
    }

    private <T> T runWithRoleLock(Long roleId, java.util.function.Supplier<T> action) {
        String key = opsRoleLockKey(roleId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithRoleKeyLock(String roleKey, java.util.function.Supplier<T> action) {
        String key = opsRoleKeyLockKey(roleKey);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithPermissionLock(Long permissionId, java.util.function.Supplier<T> action) {
        String key = opsPermissionLockKey(permissionId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "菜单权限处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithPermissionCodeLock(String permCode, java.util.function.Supplier<T> action) {
        String key = opsPermissionCodeLockKey(permCode);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "菜单权限处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithOperatorLock(Long userId, java.util.function.Supplier<T> action) {
        String key = opsOperatorLockKey(userId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运营账号处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithOperatorPhoneLock(String phone, java.util.function.Supplier<T> action) {
        String key = opsOperatorPhoneLockKey(phone);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运营账号处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }
}
