package com.aicabinet.common.dto;

import java.util.List;

public record ApprovalInboxDto(
        long pendingTaskCount,
        long unreadMessageCount,
        List<ApprovalTaskDto> pendingTasks,
        List<NotificationDto> recentMessages,
        /** 本人已处理的审批历史（含流程进度）。 */
        List<ApprovalHistoryItemDto> historyItems
) {}
