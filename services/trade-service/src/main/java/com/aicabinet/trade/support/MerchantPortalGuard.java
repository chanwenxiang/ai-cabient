package com.aicabinet.trade.support;

import com.aicabinet.trade.auth.AccessDeniedAudit;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.PermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
public class MerchantPortalGuard {
    private static final String MERCHANT_PORTAL_ACCESS = "merchant:portal:access";


    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final CabinetMetrics cabinetMetrics;
    private final AccessDeniedAudit accessDeniedAudit;

    public MerchantPortalGuard(PermissionService permissionService,
                               MerchantScopeService merchantScopeService,
                               CabinetMetrics cabinetMetrics,
                               AccessDeniedAudit accessDeniedAudit) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.cabinetMetrics = cabinetMetrics;
        this.accessDeniedAudit = accessDeniedAudit;
    }

    public void requireAccess(Long userId) {
        permissionService.requirePermission(userId, MERCHANT_PORTAL_ACCESS);
        if (merchantScopeService.isGlobalScope(userId)) {
            cabinetMetrics.recordMerchantScopeDenied("portal_global");
            accessDeniedAudit.denied(userId, MERCHANT_PORTAL_ACCESS,
                    "平台管理员请使用运营后台；商户门户需使用已绑定商户的账号登录");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "平台管理员请使用运营后台；商户门户需使用已绑定商户的账号登录");
        }
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        if (allowed.isEmpty()) {
            cabinetMetrics.recordMerchantScopeDenied("portal_no_merchant");
            accessDeniedAudit.denied(userId, MERCHANT_PORTAL_ACCESS, "账号未绑定任何商户");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
    }
}
