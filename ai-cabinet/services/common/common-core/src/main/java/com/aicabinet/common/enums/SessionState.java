package com.aicabinet.common.enums;

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

    public boolean canTransitionTo(SessionState target) {
        return switch (this) {
            case CREATED -> target == OPENING || target == CANCELLED || target == FAILED;
            case OPENING -> target == SHOPPING || target == FAILED || target == CANCELLED;
            case SHOPPING -> target == RECOGNIZING || target == WAITING_UPLOAD || target == FAILED || target == CANCELLED;
            case WAITING_UPLOAD -> target == RECOGNIZING || target == FAILED || target == CANCELLED;
            case RECOGNIZING -> target == SETTLING || target == DISPUTED || target == FAILED;
            case SETTLING -> target == COMPLETED || target == DISPUTED || target == FAILED;
            case DISPUTED -> target == COMPLETED || target == FAILED;
            default -> false;
        };
    }
}
