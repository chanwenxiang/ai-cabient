package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FundDailyBillDto;
import com.aicabinet.common.dto.FundLedgerEntryDto;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundBillServiceTest {

    @Test
    void isMerchantCreditedStatus_includesLedgerAndSettled() {
        assertTrue(FundBillService.isMerchantCreditedStatus("SUCCESS"));
        assertTrue(FundBillService.isMerchantCreditedStatus("SETTLED"));
        assertTrue(FundBillService.isMerchantCreditedStatus("LEDGER_ONLY"));
        assertFalse(FundBillService.isMerchantCreditedStatus("ACCRUED"));
        assertFalse(FundBillService.isMerchantCreditedStatus("WECHAT_SUBMITTED"));
        assertFalse(FundBillService.isMerchantCreditedStatus("VOIDED"));
    }

    @Test
    void dailyBillAggregation_skipsVoidedAndCountsLedgerOnlyAsCredited() {
        OrderRevenueSplit ledger = split("S1", "LEDGER_ONLY", 1000, 900);
        OrderRevenueSplit accrued = split("S2", "ACCRUED", 800, 720);
        OrderRevenueSplit voided = split("S3", "VOIDED", 500, 450);

        long credited = 0;
        long pending = 0;
        long gross = 0;
        for (OrderRevenueSplit s : new OrderRevenueSplit[] {ledger, accrued, voided}) {
            String status = s.getStatus().toUpperCase();
            if ("VOIDED".equals(status) || "REVERSED".equals(status)) {
                continue;
            }
            gross += s.getGrossCents();
            if (FundBillService.isMerchantCreditedStatus(status)) {
                credited += s.getMerchantCents();
            } else {
                pending += s.getMerchantCents();
            }
        }

        assertTrue(gross == 1800);
        assertTrue(credited == 900);
        assertTrue(pending == 720);
    }

    @Test
    void filterDailyBills_matchesMerchantAndDate() {
        var rows = List.of(
                new FundDailyBillDto("2026-09-01", "M001", "默认直营商户", 100, 10, 1, 80, 0, 1, true),
                new FundDailyBillDto("2026-08-31", "M002", "Demo Shop", 200, 20, 2, 160, 0, 2, true));
        assertEquals(1, FundBillService.filterDailyBills(rows, "默认").size());
        assertEquals(1, FundBillService.filterDailyBills(rows, "M002").size());
        assertEquals(1, FundBillService.filterDailyBills(rows, "2026-09").size());
        assertTrue(FundBillService.filterDailyBills(rows, "zzz_no_match").isEmpty());
    }

    @Test
    void filterLedgerRows_matchesOrderDeviceAndMerchant() {
        var row = new FundLedgerEntryDto(
                "S1:ORDER_PAYMENT", "ORDER_PAYMENT", "IN", 441,
                "M001", "默认直营商户", "CAB-001", "1788252279967241317",
                null, "WECHAT", Instant.parse("2026-09-01T08:00:00Z"));
        var deviceNames = Map.of("CAB-001", "测试柜-001");
        assertEquals(1, FundBillService.filterLedgerRows(List.of(row), "178825227996", deviceNames).size());
        assertEquals(1, FundBillService.filterLedgerRows(List.of(row), "测试柜", deviceNames).size());
        assertEquals(1, FundBillService.filterLedgerRows(List.of(row), "默认直营", deviceNames).size());
        assertTrue(FundBillService.filterLedgerRows(List.of(row), "not-a-real-keyword", deviceNames).isEmpty());
    }

    private static OrderRevenueSplit split(String id, String status, long gross, long merchant) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId(id);
        s.setStatus(status);
        s.setGrossCents(gross);
        s.setMerchantCents(merchant);
        s.setCreatedAt(Instant.parse("2024-06-01T10:00:00Z"));
        return s;
    }
}
