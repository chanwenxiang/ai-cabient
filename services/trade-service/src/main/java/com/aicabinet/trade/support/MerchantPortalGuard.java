package com.aicabinet.trade.support;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.PermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public class MerchantPortalGuard {

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final CabinetMetrics cabinetMetrics;

    public MerchantPortalGuard(PermissionService permissionService,
                               MerchantScopeService merchantScopeService,
                               CabinetMetrics cabinetMetrics) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.cabinetMetrics = cabinetMetrics;
    }

    public void requireAccess(Long userId) {
        permissionService.requirePermission(userId, "merchant:portal:access");
        if (merchantScopeService.isGlobalScope(userId)) {
            cabinetMetrics.recordMerchantScopeDenied("portal_global");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "平台管理员请使用运营后台；商户门户需使用已绑定商户的账号登录");
        }
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        if (allowed == null || allowed.isEmpty()) {
            cabinetMetrics.recordMerchantScopeDenied("portal_no_merchant");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
    }
}
