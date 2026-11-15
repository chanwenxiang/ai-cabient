package com.aicabinet.trade.service;

import com.aicabinet.common.constants.SessionTimeouts;
import com.aicabinet.trade.config.SessionExpireProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * MA-281（C23/R23X-06）：将邑 watchdog 开门判死阈值与 trade 会话过期窗口的时序契约，
 * 两侧装配期断言 + 兜底默认同源。契约本身见 {@link SessionTimeouts} 的 javadoc。
 */
class SessionTimeoutsContractTest {

    @Test
    void watchdog_belowFloor_failsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SessionTimeouts.requireWatchdogFloor(15_000));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("低于地板"));
    }

    @Test
    void watchdog_atFloor_passes() {
        assertDoesNotThrow(() -> SessionTimeouts.requireWatchdogFloor(70_000));
        assertDoesNotThrow(() -> SessionTimeouts.requireWatchdogFloor(90_000));
    }

    @Test
    void trade_atOrAboveWatchdogFloor_failsFast() {
        // 70s 与 watchdog 同点 ⇒ 60~70s 兜账窗口消失，必须装配期失败
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SessionTimeouts.requireTradeBelowWatchdog(70));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("兜账窗口"));
    }

    @Test
    void trade_belowFloor_passes() {
        assertDoesNotThrow(() -> SessionTimeouts.requireTradeBelowWatchdog(60));
    }

    @Test
    void defaults_fallback_matchesSharedConstant() {
        // 兜底默认必须与共享常量同源（原 90 是 10s 时代遗留，且过不了新断言）
        SessionExpireProperties defaults = SessionExpireProperties.defaults();
        assertEquals(SessionTimeouts.TRADE_OPENING_EXPIRE_SECONDS, defaults.openingSeconds());
    }

    @Test
    void compactConstructor_clampsAndAsserts() {
        assertEquals(60, new SessionExpireProperties(0, 30, 10, 10).openingSeconds());
        assertThrows(IllegalStateException.class,
                () -> new SessionExpireProperties(120, 30, 10, 10));
    }
}
