package com.aicabinet.common.dto;

import java.time.Instant;

/** 风控运行配置快照；source=DATABASE 表示后台覆盖，否则回落启动配置/代码默认。 */
public record RiskRuntimeConfigDto(
        int maxDisputesPer7Days,
        int defaultMaxDisputesPer7Days,
        String source,
        Instant updatedAt
) {}
