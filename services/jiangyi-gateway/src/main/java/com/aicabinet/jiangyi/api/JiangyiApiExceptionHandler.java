package com.aicabinet.jiangyi.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 设备面（/jiangyi/api/**）统一异常出口（2026-10-19 新增，第三轮审计 P2-7）。
 *
 * <p><b>成因注释</b>：此前设备面端点异常走 Spring 默认错误体（无 status/msg/data 结构，
 * HTTP 4xx/5xx），与文档口径「HTTP 200 + status 在体内」不一致；而 trade 调用的
 * 内部/管理端点（DeviceCommandController 等）必须保留 HTTP 状态码语义——故本 advice
 * 只对设备面路径生效，其余路径原样抛出。</p>
 *
 * <p>语义：鉴权失败由 DeviceAuthInterceptor 在 preHandle 处置（不经本 advice）；
 * 业务异常统一转 envelope，status 取原 HTTP 状态（404→404 等），未知异常→503
 * （设备按服务暂不可用处理，可重试；不吞栈，日志全量保留）。</p>
 */
@RestControllerAdvice(basePackageClasses = JiangyiApiExceptionHandler.JiangyiApiAdviceMarker.class)
public class JiangyiApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JiangyiApiExceptionHandler.class);

    /** 空标记接口：界定 advice 只覆盖将邑设备面控制器包。 */
    interface JiangyiApiAdviceMarker {}

    @ExceptionHandler(Exception.class)
    public Object onException(HttpServletRequest request, Exception e) {
        if (!request.getRequestURI().startsWith("/jiangyi/api/")) {
            // 非设备面（trade 调用的 /ops 内部端点等）：保持 HTTP 状态码语义
            if (e instanceof ResponseStatusException rse) {
                return ResponseEntity.status(rse.getStatusCode()).build();
            }
            log.error("jiangyi-gateway non-device endpoint error uri={}", request.getRequestURI(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        int status = e instanceof ResponseStatusException rse
                ? rse.getStatusCode().value()
                : HttpStatus.SERVICE_UNAVAILABLE.value();
        log.warn("jiangyi device endpoint error uri={} status={}: {}",
                request.getRequestURI(), status, e.getMessage());
        return Map.of("status", status, "msg", "service unavailable", "data", "");
    }
}
