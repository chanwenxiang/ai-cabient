package com.aicabinet.trade.api;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.OpsExceptionService;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-088 负向验证：商户异常台账列表必须先过门户统一闸——
 * 「未绑定⇒全局」时 allowedDeviceIds 返回 null，旧实现把 null 当「全部设备」
 * 跨租户读异常；requireAccess 拒绝全局态后该通道不复存在。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantOpsExceptionScopeTest {

    private static final long UNBOUND_USER = 5001L;
    private static final long BOUND_USER = 5002L;

    @Mock private OpsExceptionService service;
    @Mock private MerchantScopeService scopeService;
    @Mock private com.aicabinet.trade.service.PermissionService permissionService;
    @Mock private com.aicabinet.trade.metrics.CabinetMetrics cabinetMetrics;
    @Mock private com.aicabinet.trade.auth.AccessDeniedAudit accessDeniedAudit;

    private MerchantOpsExceptionController controller;

    @BeforeEach
    void setUp() {
        MerchantPortalGuard guard = new MerchantPortalGuard(
                permissionService, scopeService, cabinetMetrics, accessDeniedAudit);
        controller = new MerchantOpsExceptionController(service, scopeService, guard);

        when(scopeService.isGlobalScope(UNBOUND_USER)).thenReturn(true);
        when(scopeService.isGlobalScope(BOUND_USER)).thenReturn(false);
        when(scopeService.allowedMerchantIds(BOUND_USER)).thenReturn(Set.of("M1"));
        when(scopeService.allowedDeviceIds(BOUND_USER)).thenReturn(Set.of("D1"));
        // 旧全局态语义：未绑定 ⇒ allowedDeviceIds=null（listForDevices 曾把 null 当全量）
        when(scopeService.allowedDeviceIds(UNBOUND_USER)).thenReturn(null);
        when(service.listForDevices(any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(), 0, 20, 0));
    }

    private static MockHttpServletRequest request(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        return request;
    }

    @Test
    void list_unboundGlobalAccount_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.list(request(UNBOUND_USER), null, 0, 20));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(service, never()).listForDevices(any(), any(), anyInt(), anyInt());
    }

    @Test
    void list_boundUser_queriesOwnDeviceScope() {
        assertNotNull(controller.list(request(BOUND_USER), null, 0, 20));
        verify(service).listForDevices(eqDevices(), any(), anyInt(), anyInt());
    }

    private static Set<String> eqDevices() {
        return org.mockito.ArgumentMatchers.eq(Set.of("D1"));
    }
}
