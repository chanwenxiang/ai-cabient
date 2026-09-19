package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 值班表解析与「当前当班人」判定。
 *
 * <p>重点在<b>失败方向</b>：所有解析不了的输入都必须落到「没有值班人」（fail-closed），
 * 因为升级链一旦猜错收件人，代价是「该被叫醒的人没被叫醒」。</p>
 */
class OnCallRosterTest {

    /** 2026-09-19 是**周六**（ISO dow = 6）——刻意用周末测 days 过滤。 */
    private static final LocalDateTime SATURDAY_10 = LocalDateTime.of(2026, 9, 19, 10, 0);

    /** 2026-09-21 是**周一**（ISO dow = 1）。 */
    private static final LocalDateTime MONDAY_10 = LocalDateTime.of(2026, 9, 21, 10, 0);

    @Test
    void blankOrNullConfig_isEmpty() {
        assertTrue(OnCallRoster.parse(null).isEmpty());
        assertTrue(OnCallRoster.parse("").isEmpty());
        assertTrue(OnCallRoster.parse("   ").isEmpty());
    }

    @Test
    void malformedJson_isEmpty_failClosed() {
        assertTrue(OnCallRoster.parse("{not json").isEmpty());
        assertTrue(OnCallRoster.parse("{\"name\":\"张三\"}").isEmpty(), "非数组必须退化为空表");
        assertTrue(OnCallRoster.parse("\"张三\"").isEmpty());
    }

    @Test
    void entryWithoutPhone_isSkipped_soNobodyIsOnCall() {
        String json = "[{\"name\":\"张三\",\"phone\":\"\",\"startHour\":0,\"endHour\":24}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        assertEquals(0, roster.size());
        assertTrue(roster.currentAt(MONDAY_10).isEmpty());
    }

    @Test
    void windowIsHalfOpen_startIncludedEndExcluded() {
        String json = "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"startHour\":9,\"endHour\":18}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 9, 0)).isPresent(), "9 点整在班");
        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 17, 59)).isPresent());
        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 18, 0)).isEmpty(), "18 点整已下班");
        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 8, 59)).isEmpty());
    }

    @Test
    void overnightWindow_wrapsAroundMidnight() {
        String json = "[{\"name\":\"夜班\",\"phone\":\"13800000000\",\"startHour\":22,\"endHour\":6}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 23, 30)).isPresent());
        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 2, 0)).isPresent(), "凌晨算在夜班里");
        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 6, 0)).isEmpty(), "6 点交班");
        assertTrue(roster.currentAt(LocalDateTime.of(2026, 9, 21, 12, 0)).isEmpty());
    }

    @Test
    void daysFilter_excludesOtherWeekdays() {
        String json =
                "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"days\":[1,2,3,4,5],"
                        + "\"startHour\":9,\"endHour\":18}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        assertTrue(roster.currentAt(MONDAY_10).isPresent());
        assertTrue(roster.currentAt(SATURDAY_10).isEmpty(), "周六不在 days 里");
    }

    @Test
    void absentDays_meansEveryDay() {
        String json = "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"startHour\":0,\"endHour\":24}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        assertTrue(roster.currentAt(SATURDAY_10).isPresent());
        assertTrue(roster.currentAt(MONDAY_10).isPresent());
    }

    @Test
    void daysWithNoUsableValue_skipsEntry_insteadOfWideningToEveryDay() {
        // 回归护栏：曾写成「解析不出合法星期 ⇒ days 为空 ⇒ 每天」，那是把配置错误静默放宽成 7×24。
        assertTrue(OnCallRoster.parse(
                "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"days\":[0,8]}]").isEmpty());
        assertTrue(OnCallRoster.parse(
                "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"days\":[\"MO\"]}]").isEmpty());
        assertTrue(OnCallRoster.parse(
                "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"days\":\"1,2\"}]").isEmpty());
    }

    @Test
    void degenerateWindow_isRejected() {
        // start == end 是零长度窗口，含义不明 ⇒ 跳过条目而不是猜（曾可能被读成 24 小时）。
        assertTrue(OnCallRoster.parse(
                "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"startHour\":9,\"endHour\":9}]").isEmpty());
        assertTrue(OnCallRoster.parse(
                "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"startHour\":-1,\"endHour\":25}]").isEmpty());
    }

    @Test
    void firstMatchingEntryWins() {
        String json = "["
                + "{\"name\":\"主值班\",\"phone\":\"13800000000\",\"startHour\":9,\"endHour\":18},"
                + "{\"name\":\"备值班\",\"phone\":\"13900000000\",\"startHour\":0,\"endHour\":24}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        Optional<OnCallRoster.Entry> onCall = roster.currentAt(MONDAY_10);
        assertTrue(onCall.isPresent());
        assertEquals("主值班", onCall.get().name());
    }

    @Test
    void badEntryDoesNotPoisonGoodOnes() {
        String json = "["
                + "{\"name\":\"坏条目\",\"phone\":\"\"},"
                + "{\"name\":\"张三\",\"phone\":\"13800000000\",\"startHour\":0,\"endHour\":24}]";
        OnCallRoster roster = OnCallRoster.parse(json);

        assertEquals(1, roster.size());
        assertTrue(roster.currentAt(MONDAY_10).isPresent());
    }

    @Test
    void nameDefaultsToMaskedPhone() {
        String json = "[{\"phone\":\"13800000000\",\"startHour\":0,\"endHour\":24}]";
        OnCallRoster.Entry entry = OnCallRoster.parse(json).currentAt(MONDAY_10).orElseThrow();

        assertEquals("138****0000", entry.name());
        assertFalse(entry.name().contains("13800000000"));
    }
}
