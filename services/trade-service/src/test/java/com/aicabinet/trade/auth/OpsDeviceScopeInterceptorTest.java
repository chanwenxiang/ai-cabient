package com.aicabinet.trade.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aicabinet.trade.service.MerchantScopeService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * R23X 机制解（{@link OpsDeviceScopeInterceptor}）的四条不变量：
 * ① 路由无 {deviceId} ⇒ 不判（批量按柜筛选不是单柜越权）；
 * ② 有 {deviceId} ⇒ 交给 requireDeviceAccess（判据同源，不另立口径）；
 * ③ 越权异常原样上抛（403 不能在这里被吞成放行）；
 * ④ 无操作员身份 ⇒ 401 fail-closed（鉴权链被改坏时不能静默越权）。
 */
class OpsDeviceScopeInterceptorTest {

    private static final long OPERATOR_ID = 90000000001L;
    private static final String DEVICE_ID = "CAB-001";

    private final MerchantScopeService scope = mock(MerchantScopeService.class);
    private final OpsDeviceScopeInterceptor interceptor = new OpsDeviceScopeInterceptor(scope);

    private MockHttpServletRequest requestWithDeviceVar(String deviceId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/ops/admin/devices/x");
        Map<String, String> vars = new HashMap<>();
        if (deviceId != null) {
            vars.put("deviceId", deviceId);
        }
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        return request;
    }

    private boolean handle(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void noDevicePathVariable_passesThrough() {
        MockHttpServletRequest request = requestWithDeviceVar(null);
        request.setAttribute(AuthInterceptor.ATTR_USER_ID, OPERATOR_ID);

        assertTrue(handle(request));
        verify(scope, never()).requireDeviceAccess(any(), anyString());
    }

    @Test
    void devicePathVariable_isCheckedAgainstScope() {
        MockHttpServletRequest request = requestWithDeviceVar(DEVICE_ID);
        request.setAttribute(AuthInterceptor.ATTR_USER_ID, OPERATOR_ID);

        assertTrue(handle(request));
        verify(scope).requireDeviceAccess(OPERATOR_ID, DEVICE_ID);
    }

    @Test
    void outOfScopeDevice_propagatesForbidden() {
        MockHttpServletRequest request = requestWithDeviceVar(DEVICE_ID);
        request.setAttribute(AuthInterceptor.ATTR_USER_ID, OPERATOR_ID);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(scope)
                .requireDeviceAccess(OPERATOR_ID, DEVICE_ID);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> handle(request));
        assertTrue(ex.getStatusCode().is4xxClientError());
    }

    @Test
    void missingOperator_failsClosedWith401() {
        MockHttpServletRequest request = requestWithDeviceVar(DEVICE_ID);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> handle(request));
        assertTrue(ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED));
        verify(scope, never()).requireDeviceAccess(any(), anyString());
    }
}
