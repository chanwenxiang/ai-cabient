package com.aicabinet.trade.support;

import com.aicabinet.trade.auth.AccessDeniedAudit;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UAT §7.17 ⑥/⑥b：guard 的两条专属话术曾经一条不可达（B-13 先把双角色账号拒在 requirePermission）、
 * 另一条被通用话术顶掉（客户端只见「无权限执行此操作」，运营无法自诊断）。
 */
class MerchantPortalGuardTest {

    private static final String ACCESS = "merchant:portal:access";
    private static final long USER_ID = 100000035L;

    private PermissionService permissionService;
    private MerchantScopeService merchantScopeService;
    private CabinetMetrics cabinetMetrics;
    private AccessDeniedAudit accessDeniedAudit;
    private MerchantPortalGuard guard;

    @BeforeEach
    void setUp() {
        permissionService = mock(PermissionService.class);
        merchantScopeService = mock(MerchantScopeService.class);
        cabinetMetrics = mock(CabinetMetrics.class);
        accessDeniedAudit = mock(AccessDeniedAudit.class);
        guard = new MerchantPortalGuard(permissionService, merchantScopeService, cabinetMetrics, accessDeniedAudit);
    }

    @Test
    void globalAdmin_isRejectedWithPortalGlobalCopy_beforePermissionCheckRuns() {
        when(merchantScopeService.isGlobalScope(USER_ID)).thenReturn(true);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> guard.requireAccess(USER_ID));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("平台管理员请使用运营后台"), ex.getReason());
        verify(cabinetMetrics).recordMerchantScopeDenied("portal_global");
        // 这条 verify 才是「分支可达」的判据：requirePermission 一旦排在前面，B-13 会先把双角色账号拒成通用 403
        verify(permissionService, never()).requirePermission(any(), anyString());
    }

    @Test
    void unboundMerchantAccount_isToldWhyInsteadOfGenericDenial() {
        when(merchantScopeService.isGlobalScope(USER_ID)).thenReturn(false);
        when(merchantScopeService.allowedMerchantIds(USER_ID)).thenReturn(Set.of());

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> guard.requireAccess(USER_ID));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("账号未绑定任何商户"), ex.getReason());
        assertTrue(ex.getReason().contains("商户范围"), ex.getReason());
        verify(permissionService).requirePermission(USER_ID, ACCESS);
        verify(cabinetMetrics).recordMerchantScopeDenied("portal_no_merchant");
        verify(accessDeniedAudit).denied(eq(USER_ID), eq(ACCESS), eq("账号未绑定任何商户"));
    }

    @Test
    void boundMerchantAccount_passesWithoutScopeDenial() {
        when(merchantScopeService.isGlobalScope(USER_ID)).thenReturn(false);
        when(merchantScopeService.allowedMerchantIds(USER_ID)).thenReturn(Set.of("845172102662"));

        guard.requireAccess(USER_ID);

        verify(cabinetMetrics, never()).recordMerchantScopeDenied(anyString());
    }

    @Test
    void missingPortalAccess_stopsAtPermissionCheck() {
        when(merchantScopeService.isGlobalScope(USER_ID)).thenReturn(false);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED))
                .when(permissionService)
                .requirePermission(USER_ID, ACCESS);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> guard.requireAccess(USER_ID));

        assertEquals(ApiMessages.PERMISSION_DENIED, ex.getReason());
        verify(merchantScopeService, never()).allowedMerchantIds(any());
    }
}
