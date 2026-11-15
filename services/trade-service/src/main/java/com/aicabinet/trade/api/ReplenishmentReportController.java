package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReplenishmentStaffRowDto;
import com.aicabinet.common.dto.TallyRecordRowDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.ReplenishmentStaffReportService;
import com.aicabinet.trade.service.TallyLedgerService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final MerchantScopeService merchantScopeService;

    public ReplenishmentReportController(ReplenishmentStaffReportService reportService,
                                         TallyLedgerService tallyLedgerService,
                                         MerchantScopeService merchantScopeService) {
        this.reportService = reportService;
        this.tallyLedgerService = tallyLedgerService;
        this.merchantScopeService = merchantScopeService;
    }

    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/staff")
    public ApiResponse<List<ReplenishmentStaffRowDto>> staffReport(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(reportService.report(days));
    }

    /**
     * C2 理货记录台账：补货任务行粒度只读聚合（老系统 stock_operator_record 对位）。
     *
     * <p>R23X C44：台账是自拼 SQL 的只读聚合，此前 WHERE 里没有设备范围 ⇒ 有
     * {@code ops:replenishment:list} 的域受限账号能读到全平台明细。这里在入口把范围解析成
     * 集合下推（越权 deviceId 直接 403，而不是静默空表）。
     */
    @RequiresPermissions("ops:replenishment:list")
    @GetMapping("/tally-records")
    public ApiResponse<PageResult<TallyRecordRowDto>> tallyRecords(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "operatorUserId", required = false) Long operatorUserId,
            @RequestParam(defaultValue = "30") int days) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(tallyLedgerService.ledger(
                page, size, merchantScopeService.pushdownDeviceIds(operatorId, deviceId),
                operatorUserId, days));
    }
}
