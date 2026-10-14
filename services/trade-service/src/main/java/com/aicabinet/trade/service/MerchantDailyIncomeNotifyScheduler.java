package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.support.ScheduleZones;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户日结通知（T+1，对齐老系统「收入结算通知」，2026-10-14 用户拍板）。
 *
 * <p>每日 00:20（LineCommissionJob 同口径错峰）汇总「昨日」各商户入账
 * （{@code order_revenue_split.merchant_cents} 按创建日聚合，冲正单 VOIDED/REVERSED
 * 不计——口径与 {@link MerchantSettlementBillService} 一致），发给**昨日有收入
 * （合计 &gt;0）的商户**；0.00 不发（用户拍板，与老系统全量推送有意不同）。
 *
 * <p>渠道：站内信（merchant-mp 消息中心）。三字段对齐老系统：
 * 商户名称 / 结算周期（昨日日期）/ 收入金额。模板 {@code merchant_settlement}（V351）。
 *
 * <p>幂等：{@code biz_id=昨日日期}，发送前按 template_code+merchant_id+biz_type+biz_id
 * 查重，调度重跑不重发。本项目收益是「下单即入钱包、商户自行提现」——本通知只是
 * 昨日入账汇总的告知，不涉及任何打款动作。
 */
@Component
public class MerchantDailyIncomeNotifyScheduler {
    private static final Logger log = LoggerFactory.getLogger(MerchantDailyIncomeNotifyScheduler.class);
    private static final String PROCESS = "merchant-daily-income-notify";
    private static final String TEMPLATE_CODE = "merchant_settlement";
    private static final String BIZ_TYPE = "DAILY_INCOME";
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MerchantMapper merchantRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final NotificationService notificationService;
    private final NotificationLogMapper notificationLogRepository;
    private final ScheduledTaskService taskService;

    public MerchantDailyIncomeNotifyScheduler(MerchantMapper merchantRepository,
                                              OrderRevenueSplitMapper splitRepository,
                                              NotificationService notificationService,
                                              NotificationLogMapper notificationLogRepository,
                                              ScheduledTaskService taskService) {
        this.merchantRepository = merchantRepository;
        this.splitRepository = splitRepository;
        this.notificationService = notificationService;
        this.notificationLogRepository = notificationLogRepository;
        this.taskService = taskService;
    }

    @Scheduled(
            cron = "${aicabinet.daily-income-notify.scheduled-cron:0 20 0 * * *}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void notifyYesterdayIncome() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(PROCESS, 900)) {
            return;
        }
        boolean failed = false;
        String summary = "日结通知未执行";
        try {
            LocalDate yesterday = LocalDate.now(ScheduleZones.ZONE).minusDays(1);
            int sent = sendDailyIncomeNotify(yesterday);
            summary = "昨日(" + yesterday + ")日结通知发送 " + sent + " 商户";
            log.info("scheduled daily income notify completed: {}", summary);
        } catch (Exception e) {
            failed = true;
            taskService.finish(PROCESS, "FAILED", e.getMessage(), start);
            log.error("scheduled daily income notify failed", e);
        } finally {
            if (!failed) {
                taskService.finish(PROCESS, "SUCCESS", summary, start);
            }
        }
    }

    /** 发送昨日日结通知，返回实际发送数。包级可见供单测钉住口径。 */
    int sendDailyIncomeNotify(LocalDate yesterday) {
        Instant from = yesterday.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant to = yesterday.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        List<OrderRevenueSplit> splits = splitRepository.selectList(Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, from)
                .lt(OrderRevenueSplit::getCreatedAt, to));
        Map<String, Long> incomeByMerchant = aggregateIncome(splits);
        if (incomeByMerchant.isEmpty()) {
            return 0;
        }
        String bizId = yesterday.format(ISO_FMT);
        int sent = 0;
        for (Map.Entry<String, Long> entry : incomeByMerchant.entrySet()) {
            String merchantId = entry.getKey();
            if (alreadySent(merchantId, bizId)) {
                continue;
            }
            String merchantName = merchantRepository.findById(merchantId)
                    .map(Merchant::getMerchantName).orElse(merchantId);
            notificationService.notifyMerchant(merchantId, TEMPLATE_CODE,
                    Map.of("merchantName", merchantName,
                            "period", yesterday.format(PERIOD_FMT),
                            "amount", BigDecimal.valueOf(entry.getValue(), 2).toPlainString()),
                    BIZ_TYPE, bizId);
            sent++;
        }
        return sent;
    }

    /**
     * 纯函数：按商户聚合昨日入账（分），只保留 &gt;0 的商户（0.00 不发，用户拍板）。
     * 冲正单排除（口径对齐 {@link MerchantSettlementBillService#isReversed}）。
     */
    static Map<String, Long> aggregateIncome(List<OrderRevenueSplit> splits) {
        Map<String, Long> byMerchant = new HashMap<>();
        for (OrderRevenueSplit s : splits) {
            String merchantId = s.getMerchantId();
            if (merchantId == null || merchantId.isBlank()
                    || MerchantSettlementBillService.isReversed(s.getStatus())) {
                continue;
            }
            byMerchant.merge(merchantId, Math.max(0L, s.getMerchantCents()), Long::sum);
        }
        byMerchant.values().removeIf(v -> v <= 0);
        return byMerchant;
    }

    private boolean alreadySent(String merchantId, String bizId) {
        Long c = notificationLogRepository.selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getTemplateCode, TEMPLATE_CODE)
                .eq(NotificationLog::getAudience, "MERCHANT")
                .eq(NotificationLog::getMerchantId, merchantId)
                .eq(NotificationLog::getBizType, BIZ_TYPE)
                .eq(NotificationLog::getBizId, bizId));
        return c != null && c > 0;
    }
}
