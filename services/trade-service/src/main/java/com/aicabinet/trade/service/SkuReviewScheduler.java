package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 选品诊断每日自动刷新：近 30 天动销/库存表现写入评审表，供运营台查看与决策。 */
@Service
public class SkuReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(SkuReviewScheduler.class);

    private final SkuDelistReviewService skuReviewService;

    @Autowired
    private ScheduledTaskService taskService;

    public SkuReviewScheduler(SkuDelistReviewService skuReviewService) {
        this.skuReviewService = skuReviewService;
    }

    @Scheduled(fixedRate = 24 * 3_600_000L)
    @Transactional
    public void scan() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("sku-review-daily", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无选品评审数据";
        try {
            int rows = skuReviewService.runReview(30).size();
            summary = rows <= 0 ? "本次无选品评审数据" : "刷新选品评审 " + rows + " 条";
            if (rows > 0) {
                log.info("daily sku review refreshed rows={}", rows);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("sku-review-daily", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("sku-review-daily", "SUCCESS", summary, start);
            }
        }
    }
}
