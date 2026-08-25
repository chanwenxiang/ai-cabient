package com.aicabinet.common.dto;

/**
 * 触发 Sonar / Jenkins 扫描后的受理结果。
 */
public record DevOpsSonarScanDto(
        boolean accepted,
        String jobName,
        String queueUrl,
        String jenkinsUrl,
        String sonarDashboardUrl,
        String message
) {}
