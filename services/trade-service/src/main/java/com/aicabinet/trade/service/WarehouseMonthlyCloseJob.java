package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseDto;
import com.aicabinet.trade.support.ScheduleZones;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 每月自动生成上月仓库月结草稿（用户拍板 2026-10-13）。
 *
 * <p>口径：系统只负责算差异明细（应有 = 期初+采购入库+调入−调出−上柜−退货；实盘 = 本月最近完成盘点；
 * 差异金额 = |gap| × 目录采购成本），<b>不做任何扣款动作</b>——财务线下核对后进行实际扣钱；
 * 判责进索赔台账（CLAIM）也只是挂账。详见 {@link WarehouseMonthlyCloseSheetService}。</p>
 *
 * <p>幂等：已审批（APPROVED）的仓/月跳过（generateSheet 对 APPROVED 抛 409）；
 * DRAFT 直接重生成（草稿随时重生成是既有语义）。无负责人的仓同样生成——月结是仓维度对账，
 * 不依赖负责人（负责人用于日常出库闸门，见 SATELLITE_WAREHOUSE.md）。</p>
 */
@Service
public class WarehouseMonthlyCloseJob {

    private static final Logger log = LoggerFactory.getLogger(WarehouseMonthlyCloseJob.class);
    static final String TASK_KEY = "warehouse-monthly-close";

    /** 系统操作者：V221 约定 user_id=0 为系统操作者（显示名走「系统」兜底）。 */
    private static final long SYSTEM_OPERATOR_ID = 0L;

    private final WarehouseMonthlyCloseSheetService sheetService;
    private final WarehouseService warehouseService;
    private final ScheduledTaskService taskService;

    public WarehouseMonthlyCloseJob(WarehouseMonthlyCloseSheetService sheetService,
                                    WarehouseService warehouseService,
                                    ScheduledTaskService taskService) {
        this.sheetService = sheetService;
        this.warehouseService = warehouseService;
        this.taskService = taskService;
    }

    /** 每月 1 日 02:00（上海时区）生成上月各仓月结草稿；与 ops-fee-bill-monthly 错峰。 */
    @Scheduled(cron = "0 0 2 1 * ?", zone = ScheduleZones.ZONE_PROP)
    public void generateLastMonthSheets() {
        long taskStart = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 1800)) {
            return;
        }
        String month = YearMonth.now(ScheduleZones.ZONE).minusMonths(1).toString();
        boolean failed = false;
        try {
            List<WarehouseDto> warehouses = warehouseService.listWarehouses().stream()
                    .filter(w -> "ACTIVE".equalsIgnoreCase(w.status()))
                    .toList();
            int generated = 0;
            int skippedApproved = 0;
            int failedCount = 0;
            StringBuilder failedWarehouses = new StringBuilder();
            for (WarehouseDto warehouse : warehouses) {
                try {
                    sheetService.generateSheet(SYSTEM_OPERATOR_ID, warehouse.warehouseId(), month);
                    generated++;
                } catch (ResponseStatusException e) {
                    if (HttpStatus.CONFLICT.equals(e.getStatusCode())) {
                        // 该仓该月已审批锁定：按月自动任务重跑属预期，跳过不告警
                        skippedApproved++;
                    } else {
                        failedCount++;
                        failedWarehouses.append(warehouse.warehouseId()).append('(')
                                .append(e.getReason()).append(") ");
                        log.warn("monthly close auto-generate failed warehouse={} month={}",
                                warehouse.warehouseId(), month, e);
                    }
                } catch (Exception e) {
                    failedCount++;
                    failedWarehouses.append(warehouse.warehouseId()).append('(')
                            .append(e.getMessage()).append(") ");
                    log.error("monthly close auto-generate failed warehouse={} month={}",
                            warehouse.warehouseId(), month, e);
                }
            }
            String summary = "账期 " + month + "：生成 " + generated + " 仓，跳过已审批 "
                    + skippedApproved + " 仓" + (failedCount > 0 ? "，失败 " + failedCount + " 仓："
                    + failedWarehouses : "");
            log.info("warehouse monthly close job done: {}", summary);
            // 仓级失败不抛出（其余仓已生成），但任务状态记 FAILED 让运营台看见 partial failure
            taskService.finish(TASK_KEY, failedCount > 0 ? "FAILED" : "SUCCESS", summary, taskStart);
        } catch (Exception e) {
            failed = true;
            log.error("warehouse monthly close job failed month={}", month, e);
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), taskStart);
            throw e;
        }
    }
}
