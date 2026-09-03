package com.aicabinet.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/** 内部 API 密钥与可选来源 CIDR 白名单（trade-service / device-service 共用）。 */
@ConfigurationProperties(prefix = "aicabinet.internal-api")
public record InternalApiProperties(
        String key,
        /** 逗号分隔 CIDR，如 10.0.0.0/8,172.16.0.0/12；空=不限制 */
        String allowedCidrs
) {

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    public List<String> allowedCidrList() {
        if (allowedCidrs == null || allowedCidrs.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedCidrs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 未配置任何 CIDR 时不限制来源 IP（本地/联调默认）。 */
    public boolean hasCidrRestriction() {
        return !allowedCidrList().isEmpty();
    }
}
