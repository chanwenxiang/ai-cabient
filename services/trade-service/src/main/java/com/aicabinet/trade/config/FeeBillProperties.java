package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 周期费用（场地租金 / 流量费）出账配置。账期与 cron 均可覆盖，避免写死。
 */
@ConfigurationProperties(prefix = "aicabinet.fee-bill")
public record FeeBillProperties(
        /** 是否启用每月自动出账 */
        boolean autoGenerateEnabled,
        /** Spring cron，默认每月 1 日 01:30 */
        String autoGenerateCron,
        /** 时区 */
        String zone,
        /**
         * 相对「今天」的账期偏移月数。默认 -1 = 出上月账。
         * 0 = 当月；正数向未来，一般不用。
         */
        int billMonthOffsetMonths,
        /** 列表分页最大 size */
        int maxPageSize,
        /** 列表默认 size */
        int defaultPageSize
) {
    public FeeBillProperties {
        if (autoGenerateCron == null || autoGenerateCron.isBlank()) {
            autoGenerateCron = "0 30 1 1 * *";
        }
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Shanghai";
        }
        if (maxPageSize <= 0) {
            maxPageSize = 100;
        }
        if (defaultPageSize <= 0) {
            defaultPageSize = 20;
        }
    }

    public static FeeBillProperties defaults() {
        return new FeeBillProperties(true, "0 30 1 1 * *", "Asia/Shanghai", -1, 100, 20);
    }
}
