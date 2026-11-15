package com.aicabinet.trade.service;

import com.aicabinet.trade.config.FeeBillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 每月自动出账：场地租金 + 柜机流量费。账期/cron/开关均来自 {@link FeeBillProperties}。
 */
@Service
@ConditionalOnProperty(prefix = "aicabinet.fee-bill", name = "auto-generate-enabled", havingValue = "true", matchIfMissing = true)
public class OpsFeeBillJob {

    private static final Logger log = LoggerFactory.getLogger(OpsFeeBillJob.class);
    private static final String TASK_KEY = "ops-fee-bill-monthly";

    private final SiteRentBillService rentBillService;
    private final DeviceDataFeeBillService dataFeeBillService;
    private final FeeBillMonthResolver monthResolver;
    private final ScheduledTaskService taskService;
    private final SystemConfigService systemConfigService;

    public OpsFeeBillJob(SiteRentBillService rentBillService,
                         DeviceDataFeeBillService dataFeeBillService,
                         FeeBillMonthResolver monthResolver,
                         ScheduledTaskService taskService,
                         SystemConfigService systemConfigService) {
        this.rentBillService = rentBillService;
        this.dataFeeBillService = dataFeeBillService;
        this.monthResolver = monthResolver;
        this.taskService = taskService;
        this.systemConfigService = systemConfigService;
    }

    @Scheduled(cron = "${aicabinet.fee-bill.auto-generate-cron:0 30 1 1 * ?}",
            zone = "${aicabinet.fee-bill.zone:Asia/Shanghai}")
    public void generateMonthlyFees() {
        long taskStart = System.nanoTime();
        // 🔴 R19-24（2026-11-12）：开关判断必须在 tryBegin **之后**，与同仓 R5-E2 范式
        // （AutoReplenishmentTaskService / 短信探针）一致。原实现把开关放在 tryBegin 之前、
        // 直接 return —— 2026-10-14 流量费改日扣后本开关默认 false，于是这个**已托管给 XXL**
        // 的任务每月都「静默返回」：不写 scheduled_task.last_run_at ⇒ ScheduledTaskStaleMonitor
        // 按 max-silence（32 天）把它判成停跑，运营台长期挂一条「定时任务停跑」红点。
        // 语义上「开关关闭」不是故障，必须留一条 SKIPPED 痕迹（与「执行成功」可区分）。
        if (!taskService.tryBegin(TASK_KEY, 1800)) {
            return;
        }
        // MA-278（2026-11-15 拍板：拆开关）：原「流量费月结开关」同时门控场地租金出账，
        // 流量费改日扣后默认 false ⇒ 租金自动出账被误停（对外付款链）。拆为两个键：
        //   - feebill.site_rent.auto_generate.enabled：默认 true（租金月结出账应常态运行，
        //     租期校验由 MA-273 的 isContractEffectiveInMonth 把关，不会给未生效/到期合同出账）；
        //   - feebill.auto_generate.enabled：保留原名与默认 false，只管流量费月结出账
        //     （流量费已改日扣，月结出账属对账用途，刻意停用）。
        boolean rentEnabled = systemConfigService.getBoolean("feebill.site_rent.auto_generate.enabled", true);
        boolean dataEnabled = systemConfigService.getBoolean("feebill.auto_generate.enabled", false);
        if (!rentEnabled && !dataEnabled) {
            taskService.finish(TASK_KEY, "SKIPPED",
                    "租金/流量费月结出账开关均为 false（feebill.site_rent.auto_generate.enabled / "
                            + "feebill.auto_generate.enabled）", taskStart);
            return;
        }
        String month = monthResolver.resolve(null);
        String summary = "账期 " + month + " 无新增";
        boolean failed = false;
        try {
            int rent = rentEnabled ? rentBillService.autoGenerate(month).size() : 0;
            int data = dataEnabled ? dataFeeBillService.autoGenerate(month).size() : 0;
            summary = "账期 " + month + " 租金账单 " + rent + " 条，流量费账单 " + data + " 条";
            log.info("ops fee bill job done: {}", summary);
        } catch (Exception e) {
            failed = true;
            log.error("ops fee bill job failed month={}", month, e);
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), taskStart);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, taskStart);
            }
        }
    }
}
