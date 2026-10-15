package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * CB-021④ 敏感操作汇总行（对齐思迅「收银员异常操作」报表形态：只记录不处理）。
 */
public record AdminAuditSensitiveSummaryDto(
        String action,
        long total,
        Instant lastAt
) {}
