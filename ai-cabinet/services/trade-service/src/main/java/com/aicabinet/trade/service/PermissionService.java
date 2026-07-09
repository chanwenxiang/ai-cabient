package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.repository.OpsPermissionRepository;
import com.aicabinet.trade.repository.OpsUserRoleRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
public class PermissionService {

    private final OpsPermissionRepository permissionRepository;
    private final OpsUserRoleRepository userRoleRepository;

    public PermissionService(OpsPermissionRepository permissionRepository,
                           OpsUserRoleRepository userRoleRepository) {
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
        if (perms.contains(permCode)) {
            return true;
        }
        return perms.contains("ops:admin") || perms.stream().anyMatch(p -> p.endsWith(":*"));
    }

    public Set<String> listPermCodes(Long userId) {
        requireOperator(userId);
        return permissionRepository.findPermCodesByUserId(userId);
    }

    public boolean hasAnyRole(Long userId) {
        return !userRoleRepository.findByIdUserId(userId).isEmpty();
    }
}
