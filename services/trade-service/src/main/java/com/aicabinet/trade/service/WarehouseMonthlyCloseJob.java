package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseDto;
import com.aicabinet.trade.support.ScheduleZones;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 仓库月结草稿自动生成（V348 账期制，用户拍板 2026-10-13 节奏 A）。
 *
 * <p>🔴 <b>拍板 A = 每日 02:10 幂等检查</b>：目标账期 = 刚结束的账期（标签=结束月；
 * 今天 ≥26 日 → 本月标签，否则上月标签——账期 26 日 00:00~25 日 24:00，26 日凌晨起
 * 刚结束的账期标签是本月）。无草稿则生成；已有 DRAFT/APPROVED 跳过——
 * <b>DRAFT 不重生成</b>，保护财务在草稿上的手工补录（摇机损耗/费用抵扣/实盘修正）；
 * 人工「重新生成」按钮走 {@code generateSheet}（会删 DRAFT 重建，带确认）。
 *
 * <p>口径：系统只负责算差异明细（应有 = 期初+采购入库+调入−调出−上柜−退货；四损耗列单独展示；
 * 实盘 = 账期内最近盘点或期末盘点导入；差异金额 = |gap| × 单价快照），<b>不做任何扣款动作</b>——
 * 财务线下核对后实际扣钱；判责进索赔台账（CLAIM）也只是挂账。
 * 详见 {@link WarehouseMonthlyCloseSheetService}。
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

    /**
     * 每日 02:10（上海时区）幂等检查：刚结束账期无草稿则生成。
     * 与 ops-fee-bill-monthly（每月）错峰意义弱化（本任务每日跑但仅月末几天实际生成）。
     */
    @Scheduled(cron = "0 10 2 * * ?", zone = ScheduleZones.ZONE_PROP)
    public void ensureLastClosedPeriodSheets() {
        long taskStart = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 1800)) {
            return;
        }
        // 今天 ≥26 日 → 刚结束账期标签=本月（本月 25 日刚过）；否则=上月
        LocalDate today = LocalDate.now(ScheduleZones.ZONE);
        YearMonth target = today.getDayOfMonth() >= 26
                ? YearMonth.from(today)
                : YearMonth.from(today).minusMonths(1);
        String month = target.toString();
        try {
            List<WarehouseDto> warehouses = warehouseService.listWarehouses().stream()
                    .filter(w -> "ACTIVE".equalsIgnoreCase(w.status()))
                    .toList();
            int generated = 0;
            int skippedExisting = 0;
            int failedCount = 0;
            StringBuilder failedWarehouses = new StringBuilder();
            for (WarehouseDto warehouse : warehouses) {
                try {
                    if (sheetService.ensureDraft(SYSTEM_OPERATOR_ID, warehouse.warehouseId(), month)) {
                        generated++;
                    } else {
                        skippedExisting++;
                    }
                } catch (Exception e) {
                    failedCount++;
                    failedWarehouses.append(warehouse.warehouseId()).append('(')
                            .append(e.getMessage()).append(") ");
                    log.error("monthly close auto-generate failed warehouse={} month={}",
                            warehouse.warehouseId(), month, e);
                }
            }
            String summary = "账期 " + month + "：生成 " + generated + " 仓，已有单跳过 "
                    + skippedExisting + " 仓" + (failedCount > 0 ? "，失败 " + failedCount + " 仓："
                    + failedWarehouses : "");
            log.info("warehouse monthly close job done: {}", summary);
            // 仓级失败不抛出（其余仓已生成），但任务状态记 FAILED 让运营台看见 partial failure
            taskService.finish(TASK_KEY, failedCount > 0 ? "FAILED" : "SUCCESS", summary, taskStart);
        } catch (Exception e) {
            log.error("warehouse monthly close job failed month={}", month, e);
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), taskStart);
            throw e;
        }
    }
}
