package com.aicabinet.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/** 内部 API 密钥、可选来源 CIDR 白名单与可信代理网段（trade-service / device-service 共用）。 */
@ConfigurationProperties(prefix = "aicabinet.internal-api")
public record InternalApiProperties(
        String key,
        /** 逗号分隔 CIDR，如 10.0.0.0/8,172.16.0.0/12；空=不限制 */
        String allowedCidrs,
        /** M08：可信反代网段（逗号分隔 CIDR）。空=不信任 X-Forwarded-For/X-Real-IP（保持直连语义） */
        String trustedProxyCidrs
) {

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    public List<String> allowedCidrList() {
        return splitCidrs(allowedCidrs);
    }

    public List<String> trustedProxyCidrList() {
        return splitCidrs(trustedProxyCidrs);
    }

    /** 未配置任何 CIDR 时不限制来源 IP（本地/联调默认）。 */
    public boolean hasCidrRestriction() {
        return !allowedCidrList().isEmpty();
    }

    /** M08：仅当请求确实来自可信代理网段时才允许解析转发头。 */
    public boolean hasTrustedProxy() {
        return !trustedProxyCidrList().isEmpty();
    }

    private static List<String> splitCidrs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
