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
    private final MerchantFeaturePackService merchantFeaturePackService;

    public PermissionService(OpsPermissionMapper permissionRepository,
                           OpsUserRoleMapper userRoleRepository,
                           MerchantFeaturePackService merchantFeaturePackService) {
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.merchantFeaturePackService = merchantFeaturePackService;
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
        boolean matched = perms.contains(permCode);
        if (!matched) {
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
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            return false;
        }
        // 商户能力：RBAC ∧ 平台功能包
        if (permCode.startsWith("merchant:")) {
            return merchantFeaturePackService.isPermEnabledForUser(userId, permCode);
        }
        return true;
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
