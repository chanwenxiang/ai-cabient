package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 顶栏收件箱「审批历史」条目：本人已处理节点 + 整单流程当前进度。
 */
public record ApprovalHistoryItemDto(
        Long taskId,
        Long instanceId,
        String bizType,
        String bizId,
        String title,
        /** 本人处理的节点名称。 */
        String myNodeName,
        /** 本人任务状态：APPROVED / REJECTED。 */
        String myStatus,
        /** 整单状态：PENDING / APPROVED / REJECTED。 */
        String instanceStatus,
        /** 流程仍在进行时的当前节点名；已结束则为 null。 */
        String currentNodeName,
        /** 一行进度文案，如「已通过「运营审核」· 当前：财务审核」。 */
        String progressText,
        String actionPath,
        Instant actedAt
) {}
