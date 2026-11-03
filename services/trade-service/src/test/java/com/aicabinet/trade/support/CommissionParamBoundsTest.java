package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MA-069/090/100 同族限幅闸的钉测：越界一律 400，且话术指名道姓（运营手滑时要看得见是哪个字段）。
 */
class CommissionParamBoundsTest {

    @Test
    void bps_allRangeBoundary() {
        assertEquals(0, CommissionParamBounds.requireBps("shareBps", 0));
        assertEquals(10_000, CommissionParamBounds.requireBps("shareBps", 10_000));
    }

    @Test
    void bps_overFullAndNegative_rejected() {
        // 5000bps 本身合法，但 10001 = 100.01% 数学上不成立；负数会把佣金算成倒扣
        assertThrows(ResponseStatusException.class,
                () -> CommissionParamBounds.requireBps("commissionRateBps", 10_001));
        ResponseStatusException neg = assertThrows(ResponseStatusException.class,
                () -> CommissionParamBounds.requireBps("commissionRateBps", -1));
        assertEquals(HttpStatus.BAD_REQUEST, neg.getStatusCode());
        assertEquals("commissionRateBps 须在 0..10000（万分比，10000 = 100%）", neg.getReason());
    }

    @Test
    void bps_nullIsNotSilentZero() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> CommissionParamBounds.requireBps("shareBps", null));
        assertEquals("shareBps 不能为空", ex.getReason());
    }

    @Test
    void fixedCents_capsBothIntegerAndLongShapes() {
        assertEquals(5_000, CommissionParamBounds.requireFixedCents("commissionFixedCents", 5_000));
        assertEquals(5_000L, CommissionParamBounds.requireCents("commissionPerUnitCents", 5_000L));
        ResponseStatusException over = assertThrows(ResponseStatusException.class,
                () -> CommissionParamBounds.requireCents("commissionPerUnitCents", 5_001L));
        assertEquals("commissionPerUnitCents 须在 0..5000 分", over.getReason());
        assertThrows(ResponseStatusException.class,
                () -> CommissionParamBounds.requireFixedCents("commissionFixedCents", -1));
    }
}
