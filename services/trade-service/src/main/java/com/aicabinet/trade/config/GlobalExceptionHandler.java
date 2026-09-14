package com.aicabinet.trade.config;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.BalanceInsufficientException;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.RequestCorrelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public static final String TRACE_HEADER = "X-Trace-Id";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
        String raw = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        String message = ApiMessages.translate(raw);
        if (ex.getStatusCode().is5xxServerError()) {
            log.error("response status error {} {}", RequestCorrelation.summary(), message, ex);
        }
        return withTrace(ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.error(ex.getStatusCode().value(), message)));
    }

    /** 余额不足：与账本扣款口径一致返回 412，避免未捕获 RuntimeException 落成 500（BUG-007）。 */
    @ExceptionHandler(BalanceInsufficientException.class)
    public ResponseEntity<ApiResponse<Void>> handleBalanceInsufficient(BalanceInsufficientException ex) {
        String message = ApiMessages.translate(ex.getMessage());
        log.warn("balance insufficient {} {}", RequestCorrelation.summary(), message);
        return withTrace(ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(ApiResponse.error(HttpStatus.PRECONDITION_FAILED.value(), message)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("bad request {} {}", RequestCorrelation.summary(), ex.getMessage());
        return withTrace(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ApiMessages.translate(ex.getMessage()))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn(
                "unreadable request body {} {}",
                RequestCorrelation.summary(),
                ex.getMostSpecificCause().getMessage());
        return withTrace(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "请求体格式错误")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> ApiMessages.formatValidationFieldError(error.getField(), error.getDefaultMessage()))
                .orElse("请求参数校验失败");
        log.warn("validation failed {} {}", RequestCorrelation.summary(), message);
        return withTrace(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ApiMessages.translate(message))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        String message = "缺少参数: " + ex.getParameterName();
        log.warn("missing request parameter {} {}", RequestCorrelation.summary(), ex.getParameterName());
        return withTrace(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message)));
    }

    /** 查询参数类型不匹配（如 userId=abc）应 400，避免落入通用 500「系统繁忙」。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = Objects.toString(ex.getName(), "参数");
        String message = "参数格式错误: " + name;
        log.warn(
                "argument type mismatch {} {} value={}",
                RequestCorrelation.summary(),
                name,
                ex.getValue());
        return withTrace(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("illegal state {} {}", RequestCorrelation.summary(), ex.getMessage());
        return withTrace(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(HttpStatus.CONFLICT.value(), ApiMessages.translate(ex.getMessage()))));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return withTrace(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "资源不存在")));
    }

    /** OBS-026：不支持的 HTTP 方法应返回 405，避免落入通用 500「系统繁忙」。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String method = Objects.toString(ex.getMethod(), "UNKNOWN");
        log.warn("method not allowed {} {} {}", RequestCorrelation.summary(), method, ex.getMessage());
        return withTrace(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(HttpStatus.METHOD_NOT_ALLOWED.value(), "不支持的请求方法: " + method)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        // S-P2-2：通用 500 必须带 traceId/sessionId，便于对照网关与日志
        log.error("unhandled error {}", RequestCorrelation.summary(), ex);
        String message = ApiMessages.INTERNAL_ERROR;
        String shortTrace = RequestCorrelation.shortTraceForClient();
        if (!shortTrace.isEmpty()) {
            message = ApiMessages.INTERNAL_ERROR + "（追踪号 " + shortTrace + "）";
        }
        return withTrace(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, message)));
    }

    private static ResponseEntity<ApiResponse<Void>> withTrace(ResponseEntity<ApiResponse<Void>> response) {
        String traceId = RequestCorrelation.traceId();
        if ("-".equals(traceId)) {
            return response;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.set(TRACE_HEADER, traceId);
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }
}
