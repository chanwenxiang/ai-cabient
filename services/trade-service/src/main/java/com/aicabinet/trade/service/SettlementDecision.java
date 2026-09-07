package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;

/**
 * 结算路径决策（Pass 3A/3F）：把识别结果先归类，再进入 finalize / 争议。
 * <p>
 * 当前由 {@link SettlementService} 内联分支消费同等规则；本枚举供矩阵单测与后续抽取共用，
 * 避免神类内隐式 if 顺序漂移。
 */
public enum SettlementDecision {
    /** 可进入 finalizeOrder（高置信/已通过审单门闩）。 */
    FINALIZE,
    /** 模拟/兜底模型，需人工。 */
    HUMAN_REVIEW_MOCK,
    /** 视觉与重力不一致。 */
    HUMAN_REVIEW_MISMATCH,
    /** 视觉空、仅重力填补。 */
    HUMAN_REVIEW_GRAVITY_FILL,
    /** 其它 need_review。 */
    HUMAN_REVIEW_GENERIC;

    /**
     * 根据识别结果归类。不替代 staging/mock 重力静默结算旁路——那些仍由 SettlementService 先尝试。
     */
    public static SettlementDecision classify(VisionServiceClient.RecognitionResult recognition) {
        if (recognition == null) {
            return HUMAN_REVIEW_GENERIC;
        }
        boolean forced = SettlementService.blocksSilentSettle(recognition);
        if (!recognition.needReview() && !forced) {
            return FINALIZE;
        }
        String version = recognition.modelVersion() != null ? recognition.modelVersion().toLowerCase() : "";
        if (version.contains("gravity-mismatch")) {
            return HUMAN_REVIEW_MISMATCH;
        }
        if (version.contains("gravity-fill")) {
            return HUMAN_REVIEW_GRAVITY_FILL;
        }
        if (version.contains("mock") || version.contains("fallback")) {
            return HUMAN_REVIEW_MOCK;
        }
        if (recognition.needReview() || forced) {
            return HUMAN_REVIEW_GENERIC;
        }
        return FINALIZE;
    }

    public boolean requiresHumanReview() {
        return this != FINALIZE;
    }

    /** 与 {@link SettlementService#reviewReasonFor} 对齐的文案。 */
    public String reviewReason(VisionServiceClient.RecognitionResult recognition) {
        if (this == FINALIZE) {
            return null;
        }
        return SettlementService.reviewReasonFor(recognition);
    }
}
