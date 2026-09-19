package com.aicabinet.common.dto;

import com.aicabinet.common.enums.VisionIngestOutcome;

/**
 * 端侧识别结果直报的受理回执。
 *
 * @param accepted     本次上报是否被采纳（仅 PROCESSED 为 true）
 * @param outcome      受理结论，端侧据此决定是否重发
 * @param reason       人类可读说明（不可重发的原因/最终去向）
 * @param sessionId    回显会话号
 * @param taskId       回显端侧任务号
 * @param sessionState 处理后的会话状态（PROCESSED 时为结算后状态，否则为当前状态）
 */
public record VisionResultIngestResponseDto(
        boolean accepted,
        VisionIngestOutcome outcome,
        String reason,
        String sessionId,
        String taskId,
        String sessionState
) {}
