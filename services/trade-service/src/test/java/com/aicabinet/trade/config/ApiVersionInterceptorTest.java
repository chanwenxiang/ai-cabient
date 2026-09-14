package com.aicabinet.trade.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVersionInterceptorTest {

    private final ApiVersionInterceptor interceptor = new ApiVersionInterceptor();

    @Test
    void v2_setsResponseHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/public/config");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, new Object()));
        assertEquals("v2", res.getHeader("X-Api-Version"));
    }

    @Test
    void v3_returnsGone() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v3/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> interceptor.preHandle(req, res, new Object()));
        assertEquals(410, ex.getStatusCode().value());
    }

    @Test
    void internal_isIgnored() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/v1/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, new Object()));
        assertEquals(null, res.getHeader("X-Api-Version"));
    }
}
