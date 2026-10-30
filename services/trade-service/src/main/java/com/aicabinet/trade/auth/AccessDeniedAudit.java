package com.aicabinet.trade.auth;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.service.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
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
 *
 * <p>G2-11 ⑤（B 档白名单）：仅「授权配置类」拒绝（{@link #REASON_NO_MERCHANT_BOUND} /
 * {@link #REASON_ADMIN_USE_OPS_CONSOLE}）额外落 {@code admin_audit_log}
 * （action=ACCESS_DENIED / targetType=USER），让运营台能查到「账号没绑商户所以打不开」
 * 这类配置事故——这类拒绝低频且由配置缺陷触发，不构成扫描器刷表面；权限码类高频拒绝
 * （PERMISSION_DENIED 等）刻意不落库，避免长 path key 污染「谁做了什么」的操作账。
 * 落库与 WARN 同受 10s 去重约束；写失败只记日志，绝不改变 403 语义。
 */
@Component
public class AccessDeniedAudit {

    private static final Logger log = LoggerFactory.getLogger(AccessDeniedAudit.class);
    private static final long LOG_SUPPRESS_MS = 10_000L;
    private static final int MAX_KEYS = 10_000;

    /** G2-11 ⑤ 白名单原因：商户门户账号未绑定任何商户（配置事故，运营台必须可查）。 */
    public static final String REASON_NO_MERCHANT_BOUND = "账号未绑定任何商户";
    /** G2-11 ⑤ 白名单原因：平台管理员误入商户门户（CB-070 话术，原文一致才能命中白名单）。 */
    public static final String REASON_ADMIN_USE_OPS_CONSOLE =
            "平台管理员账号请改用运营后台（商户门户不向平台管理员开放）";

    private static final Set<String> AUDIT_WORTHY_REASONS =
            Set.of(REASON_NO_MERCHANT_BOUND, REASON_ADMIN_USE_OPS_CONSOLE);

    private final CabinetMetrics cabinetMetrics;
    private final AdminAuditService adminAuditService;
    private final ConcurrentHashMap<String, Long> lastLoggedAt = new ConcurrentHashMap<>();

    public AccessDeniedAudit(CabinetMetrics cabinetMetrics, AdminAuditService adminAuditService) {
        this.cabinetMetrics = cabinetMetrics;
        this.adminAuditService = adminAuditService;
    }

    public void denied(Long userId, String permCode, String reason) {
        String realm = realmOf(permCode);
        cabinetMetrics.recordPermissionDenied(realm);

        HttpServletRequest request = currentRequest();
        String method = request == null ? "-" : request.getMethod();
        String path = request == null ? "-" : request.getRequestURI();
        boolean auditWorthy = reason != null && AUDIT_WORTHY_REASONS.contains(reason);
        if (shouldLog(userId, method, path)) {
            log.warn("access_denied userId={} perm={} realm={} method={} path={} reason={}",
                    userId == null ? "-" : userId,
                    permCode == null || permCode.isBlank() ? "-" : permCode,
                    realm,
                    method,
                    path,
                    reason == null || reason.isBlank() ? "permission denied" : reason);
            if (auditWorthy) {
                persistDenied(userId, permCode, method, path, reason);
            }
        }
    }

    /** G2-11 ⑤：配置类拒绝落 admin_audit_log；任何写失败只记日志，绝不影响 403 抛出。 */
    private void persistDenied(Long userId, String permCode, String method, String path, String reason) {
        try {
            String detail = "perm=" + (permCode == null || permCode.isBlank() ? "-" : permCode)
                    + " method=" + method
                    + " path=" + path
                    + " reason=" + reason;
            adminAuditService.appendLog(userId, "ACCESS_DENIED", "USER",
                    userId == null ? "-" : String.valueOf(userId), detail);
        } catch (Exception e) {
            log.warn("access_denied 审计落库失败（不影响 403 语义）userId={} path={}",
                    userId == null ? "-" : userId, path, e);
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
