package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet")
public record StagingProperties(
        boolean stagingMode,
        /** 沙箱/E2E：视觉需审核或未识别时，若有重力取货信号则仍按重力结算（不影响生产 staging 校验）。 */
        boolean gravityFallbackSettle
) {}
