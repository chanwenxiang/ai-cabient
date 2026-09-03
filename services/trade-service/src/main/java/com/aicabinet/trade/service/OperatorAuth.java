package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.support.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

public final class OperatorAuth {

    private OperatorAuth() {}

    /**
     * 判断指定 userId 是否为运营账号。
     * 仅当请求上下文中的当前用户就是该 userId 时，才信任 JWT 写入的 accountType；
     * 否则回退号段（避免用「当前登录人」的类型误判其他用户）。
     */
    public static boolean isOperator(Long userId) {
        if (userId == null) {
            return false;
        }
        String accountType = accountTypeIfCurrentUser(userId);
        if (accountType != null && !accountType.isBlank()) {
            return CabinetConstants.ACCOUNT_TYPE_OPERATOR.equalsIgnoreCase(accountType.trim());
        }
        return userId >= CabinetConstants.OPERATOR_USER_ID_START;
    }

    public static boolean isOperator(UserInfo user) {
        if (user == null) {
            return false;
        }
        String accountType = user.getAccountType();
        if (accountType != null && !accountType.isBlank()) {
            return CabinetConstants.ACCOUNT_TYPE_OPERATOR.equalsIgnoreCase(accountType.trim());
        }
        return isOperator(user.getUserId());
    }

    /** 解析账号类型：优先显式字段，否则按号段回退（兼容迁移前数据）。 */
    public static String resolveAccountType(UserInfo user) {
        if (user == null) {
            return CabinetConstants.ACCOUNT_TYPE_CONSUMER;
        }
        if (user.getAccountType() != null && !user.getAccountType().isBlank()) {
            return user.getAccountType().trim().toUpperCase();
        }
        return isOperator(user.getUserId())
                ? CabinetConstants.ACCOUNT_TYPE_OPERATOR
                : CabinetConstants.ACCOUNT_TYPE_CONSUMER;
    }

    public static void requireOperator(Long userId) {
        if (!isOperator(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.OPERATOR_REQUIRED);
        }
    }

    private static String accountTypeIfCurrentUser(Long userId) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest request = sra.getRequest();
                Object uid = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
                long currentId = uid instanceof Number n ? n.longValue() : -1L;
                if (currentId != userId) {
                    return null;
                }
                Object v = request.getAttribute(AuthInterceptor.ATTR_ACCOUNT_TYPE);
                return v instanceof String s ? s : null;
            }
        } catch (IllegalStateException ignored) {
            // 非 Web 请求（定时任务/单测）
        }
        return null;
    }
}
