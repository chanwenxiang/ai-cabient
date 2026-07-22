package com.aicabinet.common.dto;

/**
 * 临期下架告警一键生成补货/下架任务。
 *
 * @param lineType PULL_OFF（默认）或 RESTOCK
 * @param assigneeUserId 可选指派人
 */
public record CreateFromExpiryRequest(String lineType, Long assigneeUserId) {
    public CreateFromExpiryRequest {
        if (lineType == null || lineType.isBlank()) {
            lineType = "PULL_OFF";
        }
    }
}
