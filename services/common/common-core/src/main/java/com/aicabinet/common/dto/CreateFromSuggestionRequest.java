package com.aicabinet.common.dto;

/**
 * CB-018 ①：补货建议一键生成补货任务。
 *
 * @param deviceId 设备 ID（必填，服务端校验）
 * @param assigneeUserId 可选指派人；空则默认操作人自己（与临期先例 createTaskFromPullOff 一致）
 */
public record CreateFromSuggestionRequest(String deviceId, Long assigneeUserId) {}
