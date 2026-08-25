package com.aicabinet.common.dto;

import java.util.List;

public record ApprovalInboxDto(
        long pendingTaskCount,
        long unreadMessageCount,
        List<ApprovalTaskDto> pendingTasks,
        List<NotificationDto> recentMessages
) {}
