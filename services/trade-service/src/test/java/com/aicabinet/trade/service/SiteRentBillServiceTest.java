package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.SiteRentSplitRule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SiteRentBillServiceTest {

    /**
     * 分摊口径（2026-10-06 起）：先扣Σfixed，剩余按份额分，余数补首条。
     * base=10001、房东 70%+fixed100、平台 30%：
     *   Σfixed=100 ⇒ 剩余 9901 ⇒ 房东 9901×70%=6930、余数 1 补首条⇒ 6931；平台 9901×30%=2970
     *   房东账单 = 6931 + 100 = 7031；平台 = 2970；合计 10001（恒等 base）
     */
    @Test
    void allocate_deductsFixedFirstThenSplitsRemainder() {
        SiteRentSplitRule landlord = rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 7000, 100);
        SiteRentSplitRule platform = rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 3000, 0);
        var lines = SiteRentBillService.allocate(10001, List.of(landlord, platform));
        assertEquals(2, lines.size());
        assertEquals(7031, lines.get(0).amountCents());
        assertEquals(2970, lines.get(1).amountCents());
        // 不变式：Σ金额恒等于 base（旧口径会得 10101）
        assertEquals(10001, lines.get(0).amountCents() + lines.get(1).amountCents());
    }

    @Test
    void allocate_withoutRules_putsAllToLandlord() {
        var lines = SiteRentBillService.allocate(5000, List.of());
        assertEquals(1, lines.size());
        assertEquals(CabinetConstants.RENT_PARTY_LANDLORD, lines.get(0).partyType());
        assertEquals(CabinetConstants.SHARE_BPS_FULL, lines.get(0).shareBps());
        assertEquals(5000, lines.get(0).amountCents());
    }

    /** H30(c)：INACTIVE 规则不参与分摊（含其 fixed），份额不并入首条 ACTIVE 规则。 */
    @Test
    void allocate_ignoresInactiveRules() {
        SiteRentSplitRule landlord = rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 7000, 100);
        SiteRentSplitRule inactive = rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 3000, 0);
        inactive.setStatus("INACTIVE");

        var lines = SiteRentBillService.allocate(10000, List.of(landlord, inactive));

        assertEquals(1, lines.size());
        assertEquals(CabinetConstants.RENT_PARTY_LANDLORD, lines.get(0).partyType());
        // Σfixed=100 ⇒ 剩余 9900 全归唯一 ACTIVE 规则：9900 + 100 = 10000
        assertEquals(10000, lines.get(0).amountCents());
    }

    /** H30(c)：status 为空白按 ACTIVE（与保存默认一致）。 */
    @Test
    void allocate_blankStatusTreatedAsActive() {
        SiteRentSplitRule landlord = rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 7000, 0);
        SiteRentSplitRule blank = rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 3000, 0);
        blank.setStatus(" ");

        var lines = SiteRentBillService.allocate(10000, List.of(landlord, blank));

        assertEquals(2, lines.size());
    }

    /** 不变式（多场景）：无论有无 fixed、fixed 如何分布，Σ账单恒等于 base。 */
    @Test
    void allocate_sumAlwaysEqualsBase_acrossScenarios() {
        int[][] scenarios = {
                // {base, landlordBps, landlordFixed, platformBps, platformFixed}
                {10001, 7000, 100, 3000, 0},
                {10000, 5000, 0, 5000, 0},
                {10000, 5000, 3000, 5000, 2000},   // Σfixed=5000，剩余 5000
                {12345, 3333, 111, 6667, 222},   // Σfixed=333，含余数补齐
                {10000, 9999, 5000, 1, 0},        // 极端份额 + 半数 fixed
        };
        for (int[] s : scenarios) {
            var lines = SiteRentBillService.allocate(s[0], List.of(
                    rule(CabinetConstants.RENT_PARTY_LANDLORD, null, s[1], s[2]),
                    rule(CabinetConstants.RENT_PARTY_PLATFORM, null, s[3], s[4])));
            int sum = 0;
            for (var l : lines) {
                sum += l.amountCents();
            }
            assertEquals(s[0], sum, "Σ账单须恒等于 base：" + java.util.Arrays.toString(s));
        }
    }

    /** 边界：Σfixed 恰好等于 base ⇒ 剩余 0，各方只拿自己的 fixed。 */
    @Test
    void allocate_fixedEqualsBase_leavesZeroRemainder() {
        var lines = SiteRentBillService.allocate(10000, List.of(
                rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 5000, 6000),
                rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 5000, 4000)));
        assertEquals(6000, lines.get(0).amountCents());
        assertEquals(4000, lines.get(1).amountCents());
        assertEquals(10000, lines.get(0).amountCents() + lines.get(1).amountCents());
    }

    /** 边界：Σfixed > base ⇒ 剩余为负、无解 ⇒ 显式 400，不得静默产生失衡账单。 */
    @Test
    void allocate_fixedExceedsBase_throws() {
        var ex = assertThrows(ResponseStatusException.class, () -> SiteRentBillService.allocate(10000, List.of(
                rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 5000, 8000),
                rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 5000, 8000))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    private static SiteRentSplitRule rule(String type, String partyId, int bps, int fixed) {
        SiteRentSplitRule r = new SiteRentSplitRule();
        r.setPartyType(type);
        r.setPartyId(partyId);
        r.setShareBps(bps);
        r.setFixedCents(fixed);
        r.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        return r;
    }
}
