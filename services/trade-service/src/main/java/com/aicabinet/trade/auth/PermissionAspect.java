package com.aicabinet.trade.auth;

import com.aicabinet.trade.service.PermissionService;
import com.aicabinet.trade.support.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Aspect
@Component
public class PermissionAspect {

    private final PermissionService permissionService;

    public PermissionAspect(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Before("@annotation(requiresPermissions)")
    public void checkMethod(RequiresPermissions requiresPermissions) {
        enforce(requiresPermissions);
    }

    @Before("@within(requiresPermissions) && !@annotation(com.aicabinet.trade.auth.RequiresPermissions)")
    public void checkType(RequiresPermissions requiresPermissions) {
        enforce(requiresPermissions);
    }

    private void enforce(RequiresPermissions requiresPermissions) {
        Long userId = currentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.MISSING_TOKEN);
        }
        String[] codes = requiresPermissions.value();
        if (codes == null || codes.length == 0) {
            return;
        }
        if (requiresPermissions.logical() == RequiresPermissions.Logical.OR) {
            permissionService.requireAnyPermission(userId, codes);
        } else {
            for (String code : codes) {
                permissionService.requirePermission(userId, code);
            }
        }
    }

    private static Long currentUserId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return null;
        }
        HttpServletRequest request = servletAttrs.getRequest();
        Object userId = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return userId instanceof Long id ? id : null;
    }
}
