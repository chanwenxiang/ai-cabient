package com.aicabinet.trade.config;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void handleGeneric_includesTraceIdInBodyAndHeader() {
        MDC.put(RequestCorrelation.TRACE_ID, "abcdef0123456789trace");
        MDC.put(RequestCorrelation.SESSION_ID, "sess-1");

        ResponseEntity<ApiResponse<Void>> res =
                handler.handleGeneric(new RuntimeException("boom"));

        assertEquals(500, res.getStatusCode().value());
        assertEquals("abcdef0123456789trace", res.getHeaders().getFirst(GlobalExceptionHandler.TRACE_HEADER));
        ApiResponse<Void> body = res.getBody();
        assertNotNull(body);
        assertTrue(body.message().contains("追踪号 abcdef012345"));
        assertTrue(body.message().startsWith(ApiMessages.INTERNAL_ERROR));
    }

    @Test
    void handleGeneric_withoutTrace_keepsPlainMessage() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleGeneric(new RuntimeException("boom"));

        ApiResponse<Void> body = res.getBody();
        assertNotNull(body);
        assertEquals(ApiMessages.INTERNAL_ERROR, body.message());
        assertTrue(res.getHeaders().get(GlobalExceptionHandler.TRACE_HEADER) == null
                || res.getHeaders().get(GlobalExceptionHandler.TRACE_HEADER).isEmpty());
    }
}
