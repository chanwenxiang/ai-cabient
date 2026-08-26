package com.aicabinet.common.dto;

/**
 * 触发 Sonar 扫描后的受理结果（GitHub Actions workflow_dispatch）。
 */
public record DevOpsSonarScanDto(
        boolean accepted,
        String jobName,
        String queueUrl,
        String actionsUrl,
        String sonarDashboardUrl,
        String message
) {}
