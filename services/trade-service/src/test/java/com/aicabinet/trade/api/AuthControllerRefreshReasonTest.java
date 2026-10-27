package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * CB-070：refresh 被拒按成因分档。状态一律 401（放行范围未变），只要求「三种成因不得共用一句」。
 */
class AuthControllerRefreshReasonTest {

    @Test
    void eachCause_getsItsOwnCopy() {
        assertEquals(
                ApiMessages.REFRESH_GRACE_EXPIRED,
                AuthController.refreshDeniedReason(JwtService.SessionReject.EXPIRED_BEYOND_GRACE));
        assertEquals(
                ApiMessages.SESSION_REVOKED_ELSEWHERE,
                AuthController.refreshDeniedReason(JwtService.SessionReject.REVOKED));
        assertEquals(
                ApiMessages.SESSION_EPOCH_RESET,
                AuthController.refreshDeniedReason(JwtService.SessionReject.BOOT_CHANGED));
    }

    /** 负向对照：成因不明（缺 boot claim/错 scope）不得冒充上面三条里任何一条。 */
    @Test
    void uncausedRejection_fallsBackToGenericInvalidToken() {
        for (JwtService.SessionReject reason :
                new JwtService.SessionReject[] {
                    JwtService.SessionReject.MISSING_BOOT,
                    JwtService.SessionReject.SCOPE_DENIED,
                    JwtService.SessionReject.UNSPECIFIED
                }) {
            String message = AuthController.refreshDeniedReason(reason);
            assertEquals(ApiMessages.INVALID_TOKEN, message, String.valueOf(reason));
            assertNotEquals(ApiMessages.SESSION_EPOCH_RESET, message);
        }
    }
}
