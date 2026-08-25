package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ReplenishmentStaffRowDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ReplenishmentStaffReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/replenishment-report")
public class ReplenishmentReportController {

    private final ReplenishmentStaffReportService reportService;

    public ReplenishmentReportController(ReplenishmentStaffReportService reportService) {
        this.reportService = reportService;
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/staff")
    public ApiResponse<List<ReplenishmentStaffRowDto>> staffReport(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(reportService.report(days));
    }
}
