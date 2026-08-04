package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/** 每日凌晨固化前一日毛利快照（改成本不回溯）。 */
@Component
public class FinanceMarginLockScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinanceMarginLockScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final FundBillService fundBillService;

    public FinanceMarginLockScheduler(FundBillService fundBillService) {
        this.fundBillService = fundBillService;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void solidifyYesterday() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        try {
            fundBillService.solidifyMargin(null, yesterday);
            log.info("finance margin solidified for {}", yesterday);
        } catch (Exception e) {
            log.warn("finance margin solidify failed for {}: {}", yesterday, e.getMessage());
        }
    }
}
