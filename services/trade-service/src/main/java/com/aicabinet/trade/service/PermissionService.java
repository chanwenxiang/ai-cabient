package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
public class PermissionService {

    private final OpsPermissionMapper permissionRepository;
    private final OpsUserRoleMapper userRoleRepository;

    public PermissionService(OpsPermissionMapper permissionRepository,
                           OpsUserRoleMapper userRoleRepository) {
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
    }

    public void requireOperator(Long userId) {
        OperatorAuth.requireOperator(userId);
    }

    public void requirePermission(Long userId, String permCode) {
        requireOperator(userId);
        if (!hasPermission(userId, permCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
    }

    public void requireAnyPermission(Long userId, String... permCodes) {
        requireOperator(userId);
        for (String permCode : permCodes) {
            if (hasPermission(userId, permCode)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
    }

    public boolean hasPermission(Long userId, String permCode) {
        if (userId == null || userId < CabinetConstants.OPERATOR_USER_ID_START) {
            return false;
        }
        // 生产安全默认：未分配角色的运营账号没有任何权限。
        // 初始化管理员必须显式绑定 admin 角色，避免新建运营号默认越权。
        if (!hasAnyRole(userId)) {
            return false;
        }
        Set<String> perms = permissionRepository.findPermCodesByUserId(userId);
        if (perms.isEmpty()) {
            return false;
        }
        if (perms.contains("ops:admin")) {
            return true;
        }
        if (permCode == null || permCode.isBlank()) {
            return false;
        }
        if (perms.contains(permCode)) {
            return true;
        }
        // 若依风格分段通配：ops:rbac:role:add ← ops:rbac:role:* / ops:rbac:* / ops:*
        String[] segments = permCode.split(":");
        for (int i = segments.length - 1; i >= 1; i--) {
            StringBuilder wildcard = new StringBuilder();
            for (int j = 0; j < i; j++) {
                if (j > 0) {
                    wildcard.append(':');
                }
                wildcard.append(segments[j]);
            }
            wildcard.append(":*");
            if (perms.contains(wildcard.toString())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyPermission(Long userId, String... permCodes) {
        if (permCodes == null) {
            return false;
        }
        for (String permCode : permCodes) {
            if (hasPermission(userId, permCode)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> listPermCodes(Long userId) {
        requireOperator(userId);
        return permissionRepository.findPermCodesByUserId(userId);
    }

    public boolean hasAnyRole(Long userId) {
        return !userRoleRepository.findByIdUserId(userId).isEmpty();
    }
}
