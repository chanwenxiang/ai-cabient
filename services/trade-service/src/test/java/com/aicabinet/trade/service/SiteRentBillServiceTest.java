package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.SiteRentSplitRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiteRentBillServiceTest {

    @Test
    void allocate_splitsBaseFeeAndAddsFixed_withRemainderToFirst() {
        SiteRentSplitRule landlord = rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 7000, 100);
        SiteRentSplitRule platform = rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 3000, 0);
        var lines = SiteRentBillService.allocate(10001, List.of(landlord, platform));
        assertEquals(2, lines.size());
        assertEquals(7101, lines.get(0).amountCents());
        assertEquals(3000, lines.get(1).amountCents());
        assertEquals(10001, lines.get(0).amountCents() - 100 + lines.get(1).amountCents());
    }

    @Test
    void allocate_withoutRules_putsAllToLandlord() {
        var lines = SiteRentBillService.allocate(5000, List.of());
        assertEquals(1, lines.size());
        assertEquals(CabinetConstants.RENT_PARTY_LANDLORD, lines.get(0).partyType());
        assertEquals(CabinetConstants.SHARE_BPS_FULL, lines.get(0).shareBps());
        assertEquals(5000, lines.get(0).amountCents());
    }

    /** H30(c)：INACTIVE 规则不参与分摊，份额不并入首条 ACTIVE 规则。 */
    @Test
    void allocate_ignoresInactiveRules() {
        SiteRentSplitRule landlord = rule(CabinetConstants.RENT_PARTY_LANDLORD, null, 7000, 100);
        SiteRentSplitRule inactive = rule(CabinetConstants.RENT_PARTY_PLATFORM, null, 3000, 0);
        inactive.setStatus("INACTIVE");

        var lines = SiteRentBillService.allocate(10000, List.of(landlord, inactive));

        assertEquals(1, lines.size());
        assertEquals(CabinetConstants.RENT_PARTY_LANDLORD, lines.get(0).partyType());
        assertEquals(10100, lines.get(0).amountCents());
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
