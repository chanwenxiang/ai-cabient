package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsFinanceAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账 / 财务看板 / SLA（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsFinanceController {

    private final OpsFinanceAdminService financeAdminService;

    public OpsFinanceController(OpsFinanceAdminService financeAdminService) {
        this.financeAdminService = financeAdminService;
    }

    // --- 对账 ---
    @RequiresPermissions("ops:reconciliation:list")
    @GetMapping("/reconciliation")
    public ApiResponse<List<PaymentReconciliationDto>> reconciliation(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(financeAdminService.listReconciliation(operatorId(request), from, to, channel));
    }

    @RequiresPermissions("ops:reconciliation:run")
    @PostMapping("/reconciliation/run")
    public ApiResponse<PaymentReconciliationDto> runReconciliation(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "WECHAT") String channel) {
        return ApiResponse.ok(financeAdminService.runReconciliation(operatorId(request), date, channel));
    }

    @RequiresPermissions("ops:reconciliation:list")
    @GetMapping("/reconciliation/{reconId}")
    public ApiResponse<PaymentReconciliationDetailDto> reconciliationDetail(
            HttpServletRequest request,
            @PathVariable Long reconId) {
        return ApiResponse.ok(financeAdminService.getReconciliationDetail(operatorId(request), reconId));
    }

    @RequiresPermissions("ops:finance:view")
    @GetMapping("/finance/stats")
    public ApiResponse<FinanceStatsDto> financeStats(HttpServletRequest request) {
        return ApiResponse.ok(financeAdminService.financeStats(operatorId(request)));
    }

    @RequiresPermissions("ops:finance:view")
    @GetMapping("/finance/report")
    public ApiResponse<FinanceReportDto> financeReport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(financeAdminService.financeReport(operatorId(request), days));
    }

    // --- SLA ---
    @RequiresPermissions("ops:sla")
    @GetMapping("/sla")
    public ApiResponse<SlaMetricsDto> sla(HttpServletRequest request) {
        return ApiResponse.ok(financeAdminService.sla(operatorId(request)));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
