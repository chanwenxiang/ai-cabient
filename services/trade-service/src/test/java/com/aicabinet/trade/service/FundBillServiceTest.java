package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
