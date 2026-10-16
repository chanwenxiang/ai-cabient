package com.aicabinet.trade.api;

import com.aicabinet.trade.service.MerchantDailyIncomeNotifyScheduler;
import com.aicabinet.trade.service.MerchantFeeLedgerService;
import com.aicabinet.trade.support.ScheduleZones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-4（2026-10-16 拍板：漏扣不自动回补，人工带日期重跑）的入口守卫。
 *
 * <p>重跑会**真扣商户钱包**，所以「哪天」必须由后端兜住：只允许过去的一天（跑今天/未来=提前扣费）、
 * 且回溯有上限（跑半年前=把商户历史翻出来扣）。负向先行：先钉这四条拒绝，再放开重跑本身。</p>
 */
class OpsMerchantFeeLedgerControllerRerunGuardTest {

    private final MerchantDailyIncomeNotifyScheduler scheduler =
            mock(MerchantDailyIncomeNotifyScheduler.class);
    private final OpsMerchantFeeLedgerController controller = new OpsMerchantFeeLedgerController(
            mock(MerchantFeeLedgerService.class), scheduler);

    /** 与被测代码同一时区，否则「昨天」在两个时区里能差一天。 */
    private static LocalDate today() {
        return LocalDate.now(ScheduleZones.ZONE);
    }

    @Test
    @DisplayName("跑今天=提前扣当天的费 ⇒ 400，且不落到扣款逻辑")
    void rejectsToday() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.rerunDailyFees(today().toString()));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("不晚于昨天"), thrown.getReason());
        verify(scheduler, never()).rerunDailyFees(any());
    }

    @Test
    @DisplayName("跑未来日期同样拒绝")
    void rejectsFutureDate() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.rerunDailyFees(today().plusDays(7).toString()));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(scheduler, never()).rerunDailyFees(any());
    }

    @Test
    @DisplayName("回溯超出上限 ⇒ 400（补扣不是翻旧账）")
    void rejectsBeyondLookbackWindow() {
        String tooOld = today().minusDays(OpsMerchantFeeLedgerController.RERUN_LOOKBACK_DAYS + 1).toString();
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.rerunDailyFees(tooOld));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains(String.valueOf(
                OpsMerchantFeeLedgerController.RERUN_LOOKBACK_DAYS)), thrown.getReason());
        verify(scheduler, never()).rerunDailyFees(any());
    }

    @Test
    @DisplayName("非 YYYY-MM-DD 直接 400，不允许「猜一个日期」扣下去")
    void rejectsUnparseableDate() {
        for (String bad : new String[] {"2026-13-45", "昨天", "2026/10/15", ""}) {
            ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                    () -> controller.rerunDailyFees(bad), bad);
            assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode(), bad);
        }
        verify(scheduler, never()).rerunDailyFees(any());
    }

    @Test
    @DisplayName("昨天与回溯上限当天是两端边界，都放行并只跑那一天")
    void acceptsYesterdayAndLookbackEdge() {
        when(scheduler.rerunDailyFees(any())).thenReturn(12_300L);

        var yesterday = controller.rerunDailyFees(today().minusDays(1).toString());
        assertEquals(today().minusDays(1).toString(), yesterday.data().get("feeDate"));
        assertEquals(12_300L, yesterday.data().get("chargedCents"));

        String edge = today().minusDays(OpsMerchantFeeLedgerController.RERUN_LOOKBACK_DAYS).toString();
        controller.rerunDailyFees(edge);

        verify(scheduler).rerunDailyFees(today().minusDays(1));
        verify(scheduler).rerunDailyFees(LocalDate.parse(edge));
    }
}
