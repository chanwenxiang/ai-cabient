package com.aicabinet.trade.auth;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.service.AdminAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * G2-11 ⑤：仅「授权配置类」拒绝（白名单两原因）落 admin_audit_log，
 * 权限码类高频拒绝不落库；写失败绝不影响 403 语义；10s 去重对落库同样生效。
 */
class AccessDeniedAuditTest {

    private static final long USER_ID = 100000035L;

    private CabinetMetrics cabinetMetrics;
    private AdminAuditService adminAuditService;
    private AccessDeniedAudit audit;

    @BeforeEach
    void setUp() {
        // 无请求上下文：method/path 记 "-"，去重 key 稳定可断言
        RequestContextHolder.setRequestAttributes(null);
        cabinetMetrics = mock(CabinetMetrics.class);
        adminAuditService = mock(AdminAuditService.class);
        audit = new AccessDeniedAudit(cabinetMetrics, adminAuditService);
    }

    @Test
    void noMerchantBound_whitelisted_persistsAuditRow() {
        audit.denied(USER_ID, "merchant:portal:access", AccessDeniedAudit.REASON_NO_MERCHANT_BOUND);

        verify(adminAuditService).appendLog(eq(USER_ID), eq("ACCESS_DENIED"), eq("USER"),
                eq(String.valueOf(USER_ID)), contains("perm=merchant:portal:access"));
        verify(adminAuditService).appendLog(anyLong(), anyString(), anyString(), anyString(),
                contains("reason=" + AccessDeniedAudit.REASON_NO_MERCHANT_BOUND));
    }

    @Test
    void adminPortalHint_whitelisted_persistsAuditRow() {
        audit.denied(USER_ID, "merchant:portal:access", AccessDeniedAudit.REASON_ADMIN_USE_OPS_CONSOLE);

        verify(adminAuditService).appendLog(eq(USER_ID), eq("ACCESS_DENIED"), eq("USER"),
                eq(String.valueOf(USER_ID)), anyString());
    }

    @Test
    void genericPermissionDenied_notWhitelisted_doesNotPersist() {
        audit.denied(USER_ID, "ops:merchant:list", "无权限执行此操作");

        verify(adminAuditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
        verify(cabinetMetrics).recordPermissionDenied("ops");
    }

    @Test
    void repeatWithinSuppressWindow_persistsOnlyOnce() {
        audit.denied(USER_ID, "merchant:portal:access", AccessDeniedAudit.REASON_NO_MERCHANT_BOUND);
        audit.denied(USER_ID, "merchant:portal:access", AccessDeniedAudit.REASON_NO_MERCHANT_BOUND);

        verify(adminAuditService, times(1)).appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void appendLogFailure_neverPropagates_keepsDenialSemantics() {
        doThrow(new RuntimeException("db down")).when(adminAuditService)
                .appendLog(any(), anyString(), anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> audit.denied(USER_ID, "merchant:portal:access",
                AccessDeniedAudit.REASON_NO_MERCHANT_BOUND));
    }
}
