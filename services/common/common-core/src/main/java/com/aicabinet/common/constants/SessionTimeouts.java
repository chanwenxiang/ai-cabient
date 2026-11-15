package com.aicabinet.common.constants;

/**
 * MA-281（C23/R23X-06）：将邑看门狗与 trade 会话过期两侧的超时常量同源。
 *
 * <p>时序契约（SessionWatchdog javadoc 同源）：trade 侧 {@code
 * aicabinet.session-expire.openingSeconds}（默认 60s，2026-11-12 真机拍板）先到点，
 * 把过期会话 CAS 进处置态（OPENING→FAILED 走兜账、RECOGNIZING→DISPUTED）；
 * 将邑 watchdog 的开门判死阈值（默认 70s）必须**晚于**该窗口，否则会话会被 watchdog
 * 直接置 FAILED——而兜账 v2 只救 CANCELLED ⇒ 真开门的弱网会话被误杀成资损争议。
 * 此前 70s 与 60s 各写各的、无任何代码强制 ⇒ 任一侧调参都可能破坏契约。</p>
 *
 * <p>两侧各自在启动/装配期断言：watchdog 的 open-timeout 不得低于
 * {@link #WATCHDOG_OPEN_TIMEOUT_MS_FLOOR}；trade 的 openingSeconds 不得达到该地板
 * （达到即与 watchdog 同点，60~70s 的兜账窗口消失）。调大任何一侧都必须同步调另一侧
 * 并改这里的常量——本类就是那根「别只改一边」的桩。</p>
 */
public final class SessionTimeouts {

    /** trade 开门会话过期秒数（SessionExpireProperties 兜底默认；2026-11-12 真机拍板 60s）。 */
    public static final long TRADE_OPENING_EXPIRE_SECONDS = 60;

    /**
     * 将邑 watchdog 开门判死阈值地板（ms）：70s = trade 60s 过期窗口 + 10s 兜账窗口。
     * 配置低于此值将在装配期失败，而不是悄悄把弱网真开门误杀成 FAILED。
     */
    public static final long WATCHDOG_OPEN_TIMEOUT_MS_FLOOR = 70_000;

    private SessionTimeouts() {
    }

    /** watchdog 装配期断言：open-timeout 低于地板即抛（启动失败优于静默资损）。 */
    public static void requireWatchdogFloor(long openTimeoutMs) {
        if (openTimeoutMs < WATCHDOG_OPEN_TIMEOUT_MS_FLOOR) {
            throw new IllegalStateException(
                    "jiangyi.watchdog.open-timeout-ms=" + openTimeoutMs + " 低于地板 "
                            + WATCHDOG_OPEN_TIMEOUT_MS_FLOOR
                            + "ms：会先于 trade 的 " + TRADE_OPENING_EXPIRE_SECONDS
                            + "s 会话过期处置生效，弱网真开门会被误判 FAILED（兜账只救 CANCELLED）。"
                            + "请与 aicabinet.session-expire.openingSeconds 同步调整（见 SessionTimeouts）");
        }
    }

    /** trade 装配期断言：openingSeconds 达到 watchdog 地板即抛（60~70s 兜账窗口被吃光）。 */
    public static void requireTradeBelowWatchdog(long openingSeconds) {
        if (openingSeconds * 1000 >= WATCHDOG_OPEN_TIMEOUT_MS_FLOOR) {
            throw new IllegalStateException(
                    "aicabinet.session-expire.opening-seconds=" + openingSeconds
                            + " 达到将邑 watchdog 判死地板 " + WATCHDOG_OPEN_TIMEOUT_MS_FLOOR
                            + "ms：60~70s 的兜账窗口消失，迟到的开门回执会撞 CANCELLED 之后无 rescue。"
                            + "请同步上调 jiangyi.watchdog.open-timeout-ms 并改 SessionTimeouts");
        }
    }
}
