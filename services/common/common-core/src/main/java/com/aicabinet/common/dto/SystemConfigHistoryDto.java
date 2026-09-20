package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 系统配置变更历史（F1 策略版本）。
 *
 * @param historyId    历史行主键，回滚接口用它指定「恢复到哪一版」
 * @param configKey    配置键
 * @param oldValue     变更前的值；首次创建时为 null（该版不可回滚）
 * @param newValue     变更后的值；删除操作时为 null
 * @param operatorId   操作人 user_id；0 = 系统
 * @param operatorName 操作人显示名（系统账号或已注销账号回落为「系统」/「账号 N」）
 * @param createdAt    变更时间
 */
public record SystemConfigHistoryDto(
        Long historyId,
        String configKey,
        String oldValue,
        String newValue,
        Long operatorId,
        String operatorName,
        Instant createdAt
) {}
