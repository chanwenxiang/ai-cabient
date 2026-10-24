package com.aicabinet.trade.config;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

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

    /** UAT §7.17 ②：钱包只读事务里的 INSERT 失败时，MyBatis 原始报文曾整段回给客户端。 */
    @Test
    void handleResponseStatus_serverErrorWithSqlDetail_isNotEchoedToClient() {
        String raw = "### The error may exist in com/aicabinet/trade/mapper/WalletAccountMapper.java\n"
                + "### SQL: INSERT INTO wallet_account (domain, owner_id) VALUES (?, ?)\n"
                + "### Cause: org.postgresql.util.PSQLException: ERROR: cannot execute INSERT"
                + " in a read-only transaction; SQL state [25006]";
        ResponseEntity<ApiResponse<Void>> res = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, raw));

        assertEquals(500, res.getStatusCode().value());
        ApiResponse<Void> body = res.getBody();
        assertNotNull(body);
        assertEquals(ApiMessages.INTERNAL_ERROR, body.message());
    }

    /** 净化只针对内部诊断；运营需要看到的业务 5xx 原因必须原样保留。 */
    @Test
    void handleResponseStatus_serverErrorWithBusinessCopy_isPreserved() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "微信退款失败：商户配置有误"));

        ApiResponse<Void> body = res.getBody();
        assertNotNull(body);
        assertEquals("微信退款失败：商户配置有误", body.message());
    }

    @Test
    void handleResponseStatus_clientError_isNeverSanitized() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该商户钱包"));

        ApiResponse<Void> body = res.getBody();
        assertNotNull(body);
        assertEquals("无权查看该商户钱包", body.message());
    }
}
