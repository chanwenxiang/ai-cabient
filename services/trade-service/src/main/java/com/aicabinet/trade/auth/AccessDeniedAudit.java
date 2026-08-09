package com.aicabinet.trade.auth;

import com.aicabinet.trade.metrics.CabinetMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限拒绝审计：记录"谁、缺哪个权限码、请求哪个接口、原因"，并同步打点。
 *
 * 用于排查"用户为什么进不去/操作被拒"：无需翻业务日志，直接检索
 * {@code access_denied} 关键字即可看到 userId + perm + method + path。
 * 无请求上下文（如定时任务内调用）时 method/path 记为 "-"。
 *
 * 防刷：同一 userId+method+path 在 LOG_SUPPRESS_MS 内只打一条 WARN，指标始终累加，
 * 避免扫描器/爆破流量刷爆日志。
 */
@Component
public class AccessDeniedAudit {

    private static final Logger log = LoggerFactory.getLogger(AccessDeniedAudit.class);
    private static final long LOG_SUPPRESS_MS = 10_000L;
    private static final int MAX_KEYS = 10_000;

    private final CabinetMetrics cabinetMetrics;
    private final ConcurrentHashMap<String, Long> lastLoggedAt = new ConcurrentHashMap<>();

    public AccessDeniedAudit(CabinetMetrics cabinetMetrics) {
        this.cabinetMetrics = cabinetMetrics;
    }

    public void denied(Long userId, String permCode, String reason) {
        String realm = realmOf(permCode);
        cabinetMetrics.recordPermissionDenied(realm);

        HttpServletRequest request = currentRequest();
        String method = request == null ? "-" : request.getMethod();
        String path = request == null ? "-" : request.getRequestURI();
        if (shouldLog(userId, method, path)) {
            log.warn("access_denied userId={} perm={} realm={} method={} path={} reason={}",
                    userId == null ? "-" : userId,
                    permCode == null || permCode.isBlank() ? "-" : permCode,
                    realm,
                    method,
                    path,
                    reason == null || reason.isBlank() ? "permission denied" : reason);
        }
    }

    private boolean shouldLog(Long userId, String method, String path) {
        String key = (userId == null ? "-" : userId) + "|" + method + "|" + path;
        long now = System.currentTimeMillis();
        Long previous = lastLoggedAt.putIfAbsent(key, now);
        if (previous == null) {
            pruneIfNeeded(now);
            return true;
        }
        if (now - previous >= LOG_SUPPRESS_MS) {
            lastLoggedAt.put(key, now);
            return true;
        }
        return false;
    }

    private void pruneIfNeeded(long now) {
        if (lastLoggedAt.size() <= MAX_KEYS) {
            return;
        }
        lastLoggedAt.entrySet().removeIf(e -> now - e.getValue() > LOG_SUPPRESS_MS);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }

    private static String realmOf(String permCode) {
        if (permCode == null) {
            return "unknown";
        }
        if (permCode.startsWith("merchant:")) {
            return "merchant";
        }
        if (permCode.startsWith("ops:")) {
            return "ops";
        }
        return "unknown";
    }
}
