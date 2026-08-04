package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.profit-sharing")
public record ProfitSharingProperties(
        boolean enabled,
        /** 本地联调：不调用微信分账 API，提交/刷新走模拟成功路径。生产必须为 false。 */
        boolean mockEnabled,
        boolean retryEnabled,
        int retryBatchSize
) {
    public ProfitSharingProperties {
        if (retryBatchSize <= 0) {
            retryBatchSize = 20;
        }
    }
}
