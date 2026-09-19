package com.aicabinet.common.enums;

/**
 * 端侧识别结果直报的受理结论（HTTP 回执，供端侧决定是否重发）。
 *
 * <p>与 Kafka 通道（fire-and-forget）不同，HTTP 入站必须把「这次上报到底有没有被采纳」
 * 明确回给端侧，否则端侧无法区分「已结算」与「被静默丢弃」。
 */
public enum VisionIngestOutcome {
    /** 会话处于 RECOGNIZING，本次结果已被采纳并完成结算（终态见回执 sessionState）。 */
    PROCESSED,
    /** 该会话此前已处理过识别结果（COMPLETED / SETTLING / DISPUTED / FAILED）——幂等，端侧勿重发。 */
    ALREADY_HANDLED,
    /** 会话尚未进入识别态（CREATED / OPENING / SHOPPING / WAITING_UPLOAD）——端侧可稍后重试。 */
    TOO_EARLY,
    /** 会话已取消——勿重发。 */
    CANCELLED
}
