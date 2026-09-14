package com.aicabinet.common.enums;

/**
 * 购物会话状态机。业务写路径须经 {@code SessionService#transition}，禁止对已有实体直接 {@code setState}
 *（新建实体赋初态除外）。
 */
public enum SessionState {
    CREATED,
    OPENING,
    SHOPPING,
    RECOGNIZING,
    WAITING_UPLOAD,
    SETTLING,
    COMPLETED,
    DISPUTED,
    FAILED,
    CANCELLED;

    /**
     * 是否允许迁移到目标态（S-P2-1）。含超时关单、运维强杀、重试识别、结案申诉等运营边。
     */
    public boolean canTransitionTo(SessionState target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case CREATED -> target == OPENING || target == CANCELLED || target == FAILED;
            case OPENING -> target == SHOPPING || target == FAILED || target == CANCELLED;
            case SHOPPING -> target == RECOGNIZING
                    || target == WAITING_UPLOAD
                    || target == COMPLETED
                    || target == FAILED
                    || target == CANCELLED;
            case WAITING_UPLOAD -> target == RECOGNIZING
                    || target == COMPLETED
                    || target == FAILED
                    || target == CANCELLED;
            case RECOGNIZING -> target == SETTLING
                    || target == DISPUTED
                    || target == FAILED
                    || target == COMPLETED
                    || target == CANCELLED;
            case SETTLING -> target == COMPLETED
                    || target == DISPUTED
                    || target == FAILED
                    || target == CANCELLED
                    || target == RECOGNIZING;
            case COMPLETED -> target == DISPUTED;
            case DISPUTED -> target == COMPLETED || target == FAILED || target == RECOGNIZING;
            case FAILED -> target == RECOGNIZING || target == COMPLETED || target == CANCELLED;
            case CANCELLED -> false;
        };
    }
}
