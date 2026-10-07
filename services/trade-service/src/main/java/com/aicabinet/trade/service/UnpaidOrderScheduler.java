package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UnpaidOrderScheduler {
    private static final String UNPAID_CANCEL = "unpaid-cancel";

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderScheduler.class);

    private final UnpaidOrderService unpaidOrderService;
    private final ScheduledTaskService taskService;

    public UnpaidOrderScheduler(UnpaidOrderService unpaidOrderService,
                                ScheduledTaskService taskService) {
        this.unpaidOrderService = unpaidOrderService;
        this.taskService = taskService;
    }

    /**
     * 待支付欠款的**唯一**定时入口：先催缴（V325，CB-003），再关单（既有逻辑）。
     *
     * <p>🔴 为什么催缴**不单独**加一个 {@code @Scheduled}：
     * <ol>
     *   <li><b>容量约束</b>：{@code SchedulingPoolCapacitySelfCheck} 判
     *       「@Scheduled 并发 ≤ Hikari 连接池上限」。本仓调度池为 8，单加一个新调度
     *       会把并发顶到连接池上限 —— 实测测试环境 <b>8 &gt; 5</b>，架构自检直接报风险，
     *       而 {@code check-scheduling-vs-db-pool} 门禁也是同一口径 ⇒ 调度并发不是能随便加的。</li>
     *   <li><b>业务上本就该同批</b>：催缴必须在<b>关单之前</b> —— 订单一旦 CANCELLED，
     *       用户就没有可缴的对象了，催缴失去意义（CB-003：催缴窗口应在超时关单之前）。</li>
     * </ol>
     * 两者叠加 ⇒ 合并到同一轮「取 PENDING 单 → 催 → 关」，而不是拆两个调度。
     *
     * <p>🔴 架构约束（{@code TradeArchitectureTest.scheduledMustCallTryBegin}）：
     * {@code @Scheduled} 方法**自身必须**可见 {@code taskService.tryBegin(...)} 调用
     *（ArchUnit 判 {@code getMethodCallsFromSelf()}，抽到私有工具方法会被判红）。
     * 所以这里显式调用，**不要**为了「去重」把它藏进 helper。
     */
    @Scheduled(fixedDelayString = "${aicabinet.unpaid.auto-cancel-interval-ms:900000}", initialDelay = 180_000)
    public void autoCancelExpired() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(UNPAID_CANCEL, 600)) {
            return;
        }
        String summary;
        try {
            // ① 先催缴（V325）：还没到关单时点的 PENDING 单发短信，给用户补缴机会
            int reminded = unpaidOrderService.autoRemindUnpaidOrders();
            // ② 再关单（既有逻辑）：已超过超时阈值的单取消 + 回滚库存
            int cancelled = unpaidOrderService.autoCancelExpired();
            summary = (reminded <= 0 ? "催缴 0 笔" : "催缴 " + reminded + " 笔")
                    + "；取消超时未付订单 " + cancelled + " 笔";
            if (reminded > 0 || cancelled > 0) {
                log.info("unpaid sweep done reminded={} cancelled={}", reminded, cancelled);
            }
        } catch (Exception e) {
            // 失败必须记 FAILED：吞掉异常却记 SUCCESS 会让任务看板变成假绿
            taskService.finish(UNPAID_CANCEL, "FAILED", e.getMessage(), start);
            log.warn("unpaid sweep failed", e);
            return;
        }
        taskService.finish(UNPAID_CANCEL, "SUCCESS", summary, start);
    }
}
