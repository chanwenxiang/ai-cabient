package com.aicabinet.trade.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private SessionCookieService sessionCookieService;

    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(jwtService, sessionCookieService);
    }

    @Test
    void cookieAuth_opsPathUsesPathAwareResolve_notRawConsumerCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/ops/admin/devices");
        request.setRequestURI("/api/v2/ops/admin/devices");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(sessionCookieService.resolveTokenForPath(eq(request), eq("/api/v2/ops/admin/devices")))
                .thenReturn("admin-jwt");
        when(jwtService.validateAndGetPrincipal("admin-jwt"))
                .thenReturn(new JwtService.SessionPrincipal(100000001L, "OPERATOR", "jti1", null));

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(100000001L, request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        assertEquals("OPERATOR", request.getAttribute(AuthInterceptor.ATTR_ACCOUNT_TYPE));
        verify(sessionCookieService).resolveTokenForPath(request, "/api/v2/ops/admin/devices");
    }

    @Test
    void bearerTakesPrecedenceOverCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/ops/admin/devices");
        request.setRequestURI("/api/v2/ops/admin/devices");
        request.addHeader("Authorization", "Bearer bearer-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtService.validateAndGetPrincipal("bearer-jwt"))
                .thenReturn(new JwtService.SessionPrincipal(100000001L, "OPERATOR", "jti2", null));

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(100000001L, request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        assertEquals("OPERATOR", request.getAttribute(AuthInterceptor.ATTR_ACCOUNT_TYPE));
        verify(jwtService).validateAndGetPrincipal(anyString());
    }
}
