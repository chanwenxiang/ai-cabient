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
    /**
     * CB-070：超管分支只说「去哪」——旧文案后半句「需用已绑定商户的账号登录」对超管是无关成因，
     * 只会把他引去查自己根本无关的商户绑定。
     */
    private static final String ADMIN_HINT = "平台管理员账号请改用运营后台（商户门户不向平台管理员开放）";
    /** CB-070：通用「无权限执行此操作」不指出该配哪一类角色，运营只能猜是绑定掉了还是权限没给。 */
    private static final String PERMISSION_HINT =
            "该账号未被分配「商户门户」访问权限，请联系运营在「角色管理」为其绑定含商户门户权限的角色";

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
        // 超管判定必须在 requirePermission 之前：B-13 让 ops:admin 权限集对任何 merchant:* 直接 false，
        // 放在后面时这段专属话术与 portal_global 指标永远走不到（UAT §7.17 用双角色账号 + 指标 series 缺席证伪）。
        // 三条分支都是 403，只换话术、各说各的成因（CB-070），不改变放行范围。
        if (merchantScopeService.isGlobalScope(userId)) {
            cabinetMetrics.recordMerchantScopeDenied("portal_global");
            accessDeniedAudit.denied(userId, MERCHANT_PORTAL_ACCESS, ADMIN_HINT);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ADMIN_HINT);
        }
        try {
            permissionService.requirePermission(userId, MERCHANT_PORTAL_ACCESS);
        } catch (ResponseStatusException denied) {
            // 审计已由 requirePermission 落过，这里只把通用话术换成可自助诊断的一句
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION_HINT, denied);
        }
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        if (allowed.isEmpty()) {
            cabinetMetrics.recordMerchantScopeDenied("portal_no_merchant");
            accessDeniedAudit.denied(userId, MERCHANT_PORTAL_ACCESS, "账号未绑定任何商户");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "账号未绑定任何商户，请联系运营在「账号管理 → 商户范围」里勾选商户");
        }
    }
}
