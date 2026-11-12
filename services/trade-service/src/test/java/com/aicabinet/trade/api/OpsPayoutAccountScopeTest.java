package com.aicabinet.trade.api;

import com.aicabinet.common.dto.PayoutAccountCreateRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.PayoutAccountService;
import com.aicabinet.trade.service.PermissionService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R19-15 负向验证：{@code ops:merchant:edit} 只是功能码，不含商户维度。
 *
 * <p>原实现只查功能码，被授权管 MCH-A 的运营可以拿 {@code ownerId=MCH-B} 建/改默认收款账户，
 * 资金改道。这里钉住三条：
 * <ol>
 *   <li>商户不在数据范围内 ⇒ 403，且<b>服务层一次都没被调</b>（不是「查完再拦」）；</li>
 *   <li>商户在范围内 ⇒ 放行；</li>
 *   <li>线长域<b>不</b>调商户范围闸（无同类域模型，见控制器注释「有意不同」）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsPayoutAccountScopeTest {

    private static final long OPERATOR = 9001L;

    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private MerchantScopeService merchantScopeService;

    private OpsPayoutAccountController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new OpsPayoutAccountController(
                payoutAccountService, permissionService, auditService, merchantScopeService);
        request = new MockHttpServletRequest();
        request.setAttribute(AuthInterceptor.ATTR_USER_ID, OPERATOR);
        when(payoutAccountService.create(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any())).thenReturn(new PayoutAccount());
    }

    private static PayoutAccountCreateRequest body(String ownerType, String ownerId) {
        return new PayoutAccountCreateRequest(ownerType, ownerId, "BANK", "WECHAT",
                "杭州某某商行", "6222000000000001", "招商银行", "杭州分行", "91330100MA2XXXX",
                true);
    }

    @Test
    void create_merchantOutsideScope_forbiddenAndServiceNotCalled() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(merchantScopeService).requireMerchantAccess(OPERATOR, "MCH-B");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(request, body("MERCHANT", "MCH-B")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(payoutAccountService, never()).create(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any());
        verify(auditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void create_merchantInScope_allowed() {
        controller.create(request, body("MERCHANT", "MCH-A"));

        verify(merchantScopeService).requireMerchantAccess(OPERATOR, "MCH-A");
        verify(payoutAccountService).create(eq("MERCHANT"), eq("MCH-A"), any(), any(), any(),
                any(), any(), any(), any(), eq(true), eq(OPERATOR));
    }

    @Test
    void list_merchantOutsideScope_forbidden() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(merchantScopeService).requireMerchantAccess(OPERATOR, "MCH-B");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.list(request, "MERCHANT", "MCH-B"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(payoutAccountService, never()).listByOwner(any(), any());
    }

    @Test
    void setDefault_merchantOutsideScope_forbidden() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(merchantScopeService).requireMerchantAccess(OPERATOR, "MCH-B");

        assertThrows(ResponseStatusException.class,
                () -> controller.setDefault(request, 42L, body("MERCHANT", "MCH-B")));

        verify(payoutAccountService, never()).setDefault(any(), any(), anyLong());
    }

    @Test
    void lineManager_doesNotGoThroughMerchantScope() {
        controller.create(request, body("LINE_MANAGER", "LM-001"));

        verify(merchantScopeService, never()).requireMerchantAccess(anyLong(), anyString());
        verify(permissionService).requirePermission(OPERATOR, "ops:line-manager:edit");
    }
}
