package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.ShoppingSession;

/**
 * 会话关键路径结构化日志字段（S-P2-9）：统一 sessionId / deviceId / userId，便于检索。
 */
public final class SessionLogContext {

    private SessionLogContext() {}

    public static String of(ShoppingSession session) {
        if (session == null) {
            return "sessionId=- deviceId=- userId=-";
        }
        return "sessionId="
                + nullToDash(session.getSessionId())
                + " deviceId="
                + nullToDash(session.getDeviceId())
                + " userId="
                + nullToDash(session.getUserId());
    }

    public static String of(String sessionId, String deviceId, String userId) {
        return "sessionId="
                + nullToDash(sessionId)
                + " deviceId="
                + nullToDash(deviceId)
                + " userId="
                + nullToDash(userId);
    }

    private static String nullToDash(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "-" : text;
    }
}
