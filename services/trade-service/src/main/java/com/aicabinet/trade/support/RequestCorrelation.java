package com.aicabinet.trade.support;

import org.slf4j.MDC;

/**
 * 从 MDC / micrometer-tracing 读取关联字段，供异常日志与对客文案引用。
 */
public final class RequestCorrelation {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String SESSION_ID = "sessionId";

    private RequestCorrelation() {}

    public static String mdcOrDash(String key) {
        String value = MDC.get(key);
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim();
    }

    public static String traceId() {
        return mdcOrDash(TRACE_ID);
    }

    /** 日志一行摘要：traceId / spanId / sessionId */
    public static String summary() {
        return "traceId="
                + mdcOrDash(TRACE_ID)
                + " spanId="
                + mdcOrDash(SPAN_ID)
                + " sessionId="
                + mdcOrDash(SESSION_ID);
    }

    /** 对客展示用短追踪号（过长 UUID 截断），无 trace 时返回空串 */
    public static String shortTraceForClient() {
        String traceId = mdcOrDash(TRACE_ID);
        if ("-".equals(traceId)) {
            return "";
        }
        return traceId.length() <= 12 ? traceId : traceId.substring(0, 12);
    }
}
