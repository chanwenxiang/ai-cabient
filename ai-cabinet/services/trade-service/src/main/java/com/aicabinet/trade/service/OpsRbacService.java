package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.OpsRolePermission;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.repository.OpsPermissionRepository;
import com.aicabinet.trade.repository.OpsRolePermissionRepository;
import com.aicabinet.trade.repository.OpsRoleRepository;
import com.aicabinet.trade.repository.OpsUserRoleRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class OpsRbacService {

    private final OpsRoleRepository roleRepository;
    private final OpsPermissionRepository permissionRepository;
    private final OpsRolePermissionRepository rolePermissionRepository;
    private final OpsUserRoleRepository userRoleRepository;
    private final PermissionService permissionService;
    private final UserInfoRepository userInfoRepository;

    public OpsRbacService(OpsRoleRepository roleRepository,
                          OpsPermissionRepository permissionRepository,
                          OpsRolePermissionRepository rolePermissionRepository,
                          OpsUserRoleRepository userRoleRepository,
                          PermissionService permissionService,
                          UserInfoRepository userInfoRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.permissionService = permissionService;
        this.userInfoRepository = userInfoRepository;
    }

    @Transactional(readOnly = true)
    public List<OpsRoleDto> listRoles(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:rbac:role");
        return roleRepository.findAll().stream()
                .map(r -> {
                    int permCount = rolePermissionRepository.findPermissionIdsByRoleId(r.getRoleId()).size();
                    return new OpsRoleDto(
                            r.getRoleId(), r.getRoleKey(), r.getRoleName(),
                            r.getStatus(), r.getRemark(), List.of(permCount + " 项权限"));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OpsPermissionDto> listPermissions(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:rbac:role");
        return permissionRepository.findByStatusOrderBySortOrderAsc("ACTIVE").stream()
                .map(p -> new OpsPermissionDto(
                        p.getPermissionId(), p.getParentId(), p.getPermCode(),
                        p.getPermName(), p.getPermType(), p.getPath(), p.getSortOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OpsRolePermissionsDto getRolePermissions(Long operatorId, Long roleId) {
        permissionService.requirePermission(operatorId, "ops:rbac:role");
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ROLE_NOT_FOUND));
        List<Long> permissionIds = rolePermissionRepository.findPermissionIdsByRoleId(roleId);
        return new OpsRolePermissionsDto(role.getRoleId(), role.getRoleKey(), role.getRoleName(), permissionIds);
    }

    @Transactional
    public OpsRolePermissionsDto assignRolePermissions(Long operatorId, Long roleId, List<Long> permissionIds) {
        permissionService.requirePermission(operatorId, "ops:rbac:role");
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ROLE_NOT_FOUND));
        if ("admin".equals(role.getRoleKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_MODIFY_ADMIN_ROLE);
        }
        rolePermissionRepository.deleteByIdRoleId(roleId);
        if (permissionIds != null) {
            for (Long permissionId : permissionIds) {
                if (!permissionRepository.existsById(permissionId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ApiMessages.INVALID_REQUEST + "：permissionId=" + permissionId);
                }
                rolePermissionRepository.save(new OpsRolePermission(roleId, permissionId));
            }
        }
        return getRolePermissions(operatorId, roleId);
    }

    @Transactional(readOnly = true)
    public PageResult<OpsOperatorDto> listOperators(Long operatorId, int page, int size, String phone) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign");
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

    @Transactional(readOnly = true)
    public OpsUserRolesDto getUserRoles(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign");
        ensureOperatorAccount(userId);
        List<Long> roleIds = userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> ur.getId().getRoleId())
                .toList();
        Set<String> perms = permissionRepository.findPermCodesByUserId(userId);
        return new OpsUserRolesDto(userId, roleIds, perms.stream().toList());
    }

    @Transactional
    public OpsUserRolesDto assignRoles(Long operatorId, Long userId, List<Long> roleIds) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign");
        ensureOperatorAccount(userId);
        userRoleRepository.deleteByIdUserId(userId);
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                if (!roleRepository.existsById(roleId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ApiMessages.INVALID_REQUEST + "：roleId=" + roleId);
                }
                userRoleRepository.save(new OpsUserRole(userId, roleId));
            }
        }
        return getUserRoles(operatorId, userId);
    }

    @Transactional(readOnly = true)
    public Set<String> myPermissions(Long operatorId) {
        permissionService.requireOperator(operatorId);
        return permissionService.listPermCodes(operatorId);
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
                .map(r -> r.getRoleName())
                .toList();
        int permCount = permissionRepository.findPermCodesByUserId(operatorId).size();
        return new OpsMeDto(
                operatorId,
                user.getPhoneNumber(),
                user.getName(),
                roleNames,
                permCount
        );
    }

    private OpsOperatorDto toOperatorDto(UserInfo user) {
        List<Long> roleIds = userRoleRepository.findByIdUserId(user.getUserId()).stream()
                .map(ur -> ur.getId().getRoleId())
                .toList();
        List<String> roleNames = roleIds.stream()
                .flatMap(id -> roleRepository.findById(id).stream())
                .map(r -> r.getRoleName())
                .toList();
        return new OpsOperatorDto(
                user.getUserId(),
                user.getPhoneNumber(),
                user.getName(),
                roleNames,
                roleIds
        );
    }

    private void ensureOperatorAccount(Long userId) {
        if (userId == null || userId < CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.NOT_OPERATOR_ACCOUNT);
        }
        if (!userInfoRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND);
        }
    }
}
