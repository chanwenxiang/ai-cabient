package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 商户月度结算单重建调度（CB-020 ②）。
 *
 * <p>每日 02:40（对账 01:30 之后）重建「上月 + 本月」两张账期：上月为关账终值，
 * 本月为滚动快照。错峰在 reconciliation（01:30）之后，避免与资金域任务抢连接池
 * （铁律：@Scheduled 并发数 ≤ Hikari 池上限）。</p>
 */
@Component
public class MerchantSettlementBillScheduler {
    private static final String SETTLEMENT_BILL = "settlement-bill";

    private static final Logger log = LoggerFactory.getLogger(MerchantSettlementBillScheduler.class);

    private final MerchantSettlementBillService settlementBillService;
    private final ScheduledTaskService taskService;

    public MerchantSettlementBillScheduler(MerchantSettlementBillService settlementBillService,
                                           ScheduledTaskService taskService) {
        this.settlementBillService = settlementBillService;
        this.taskService = taskService;
    }

    @Scheduled(
            cron = "${aicabinet.settlement-bill.scheduled-cron:0 40 2 * * *}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void rebuildMonthlyBills() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(SETTLEMENT_BILL, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "结算单重建未执行";
        try {
            YearMonth current = YearMonth.now(ScheduleZones.ZONE);
            YearMonth previous = current.minusMonths(1);
            int merchants = settlementBillService.rebuildMonth(previous)
                    + settlementBillService.rebuildMonth(current);
            summary = "结算单重建 " + previous + " / " + current + " 共 " + merchants + " 商户";
            log.info("scheduled settlement bill rebuild completed: {}", summary);
        } catch (Exception e) {
            failed = true;
            taskService.finish(SETTLEMENT_BILL, "FAILED", e.getMessage(), start);
            log.error("scheduled settlement bill rebuild failed", e);
        } finally {
            if (!failed) {
                taskService.finish(SETTLEMENT_BILL, "SUCCESS", summary, start);
            }
        }
    }
}
