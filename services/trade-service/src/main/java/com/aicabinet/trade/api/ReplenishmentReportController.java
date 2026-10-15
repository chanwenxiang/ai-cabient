package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReplenishmentStaffRowDto;
import com.aicabinet.common.dto.TallyRecordRowDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ReplenishmentStaffReportService;
import com.aicabinet.trade.service.TallyLedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/replenishment-report")
public class ReplenishmentReportController {

    private final ReplenishmentStaffReportService reportService;
    private final TallyLedgerService tallyLedgerService;

    public ReplenishmentReportController(ReplenishmentStaffReportService reportService,
                                         TallyLedgerService tallyLedgerService) {
        this.reportService = reportService;
        this.tallyLedgerService = tallyLedgerService;
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/staff")
    public ApiResponse<List<ReplenishmentStaffRowDto>> staffReport(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(reportService.report(days));
    }

    /** C2 理货记录台账：补货任务行粒度只读聚合（老系统 stock_operator_record 对位）。 */
    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/tally-records")
    public ApiResponse<PageResult<TallyRecordRowDto>> tallyRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "operatorUserId", required = false) Long operatorUserId,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(tallyLedgerService.ledger(page, size, deviceId, operatorUserId, days));
    }
}
