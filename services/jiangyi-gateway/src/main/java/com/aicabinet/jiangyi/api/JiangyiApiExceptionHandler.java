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
 * <p><b>成因注释</b>：设备面端点异常若走 Spring 默认错误体，就没有 {@code status/msg/data}
 * 结构，固件按 envelope 解析会直接抛；而 trade 调用的内部/管理端点（DeviceCommandController
 * 等）必须保留裸 HTTP 状态码语义——故本 advice 只对设备面路径生效，其余路径原样返回。</p>
 *
 * <p>语义：鉴权失败由 DeviceAuthInterceptor 在 preHandle 抛
 * {@code ResponseStatusException(401/403)}，未注册路径由 DeviceUnknownPathController 抛 404，
 * 一并在这里转成将邑 envelope。文档字段表只规定 {@code status} 是 int、200 成功、其他皆失败，
 * <b>没有规定 HTTP 码</b> ⇒ 取值 <b>HTTP 码与体内 status 同值</b>：设备按体内 status 判成败，
 * nginx／监控／我方 IT 按 HTTP 码判，两套口径不许互相掩盖。（2026-10-19 修正：初版只改体内码、
 * HTTP 恒 200，把 DeviceAuthIT 的 401/403/404 断言整体吞掉——该类命名 {@code *IT}，
 * surefire 默认不跑，缺陷随「CI 绿」入库。）</p>
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
        return ResponseEntity.status(status)
                .body(Map.of("status", status, "msg", reason(status), "data", ""));
    }

    private static String reason(int status) {
        return switch (status) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not found";
            default -> "service unavailable";
        };
    }
}
