package com.aicabinet.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStateTest {

    @Test
    void happyPath_openToSettle() {
        assertTrue(SessionState.CREATED.canTransitionTo(SessionState.OPENING));
        assertTrue(SessionState.OPENING.canTransitionTo(SessionState.SHOPPING));
        assertTrue(SessionState.SHOPPING.canTransitionTo(SessionState.RECOGNIZING));
        assertTrue(SessionState.RECOGNIZING.canTransitionTo(SessionState.SETTLING));
        assertTrue(SessionState.SETTLING.canTransitionTo(SessionState.COMPLETED));
    }

    @Test
    void opsAndTimeoutEdges() {
        assertTrue(SessionState.RECOGNIZING.canTransitionTo(SessionState.COMPLETED));
        assertTrue(SessionState.WAITING_UPLOAD.canTransitionTo(SessionState.COMPLETED));
        assertTrue(SessionState.RECOGNIZING.canTransitionTo(SessionState.CANCELLED));
        assertTrue(SessionState.SETTLING.canTransitionTo(SessionState.CANCELLED));
        assertTrue(SessionState.FAILED.canTransitionTo(SessionState.RECOGNIZING));
        assertTrue(SessionState.DISPUTED.canTransitionTo(SessionState.RECOGNIZING));
        assertTrue(SessionState.COMPLETED.canTransitionTo(SessionState.DISPUTED));
        assertTrue(SessionState.FAILED.canTransitionTo(SessionState.COMPLETED));
    }

    @Test
    void terminalCancelled_blocksFurtherMoves() {
        assertFalse(SessionState.CANCELLED.canTransitionTo(SessionState.OPENING));
        assertFalse(SessionState.CANCELLED.canTransitionTo(SessionState.COMPLETED));
        assertFalse(SessionState.COMPLETED.canTransitionTo(SessionState.CANCELLED));
        assertFalse(SessionState.COMPLETED.canTransitionTo(SessionState.SHOPPING));
    }
}
