package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AmapGeocodeService;
import com.aicabinet.trade.service.CompetitiveGapService;
import com.aicabinet.trade.service.DeviceAssetService;
import com.aicabinet.trade.service.FundBillService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsGapFeaturesController {

    private final FundBillService fundBillService;
    private final CompetitiveGapService gapService;
    private final DeviceAssetService deviceAssetService;
    private final AmapGeocodeService amapGeocodeService;

    public OpsGapFeaturesController(FundBillService fundBillService,
                                    CompetitiveGapService gapService,
                                    DeviceAssetService deviceAssetService,
                                    AmapGeocodeService amapGeocodeService) {
        this.fundBillService = fundBillService;
        this.gapService = gapService;
        this.deviceAssetService = deviceAssetService;
        this.amapGeocodeService = amapGeocodeService;
    }

    // ---- geo ----

    @RequiresPermissions("ops:device:edit")
    @GetMapping("/geo/geocode")
    public ApiResponse<GeocodeResponse> geocode(@RequestParam("address") String address) {
        return ApiResponse.ok(amapGeocodeService.geocode(address));
    }

    @RequiresPermissions("ops:device:edit")
    @GetMapping("/geo/status")
    public ApiResponse<java.util.Map<String, Boolean>> geoStatus() {
        return ApiResponse.ok(java.util.Map.of("configured", amapGeocodeService.isConfigured()));
    }

    // ---- M1 fund ----

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/fund/daily-bills")
    public ApiResponse<List<FundDailyBillDto>> dailyBills(
            HttpServletRequest request,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return ApiResponse.ok(fundBillService.listDailyBills(operatorId(request), fromDate, toDate));
    }

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/fund/ledger")
    public ApiResponse<PageResult<FundLedgerEntryDto>> ledger(
            HttpServletRequest request,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String financialType,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(fundBillService.listLedger(
                operatorId(request), fromDate, toDate, financialType, direction, page, size));
    }

    @RequiresPermissions("ops:fund:export")
    @GetMapping(value = "/fund/daily-bills/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportDailyBills(
            HttpServletRequest request,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        byte[] csv = fundBillService.exportDailyBillsCsv(operatorId(request), fromDate, toDate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fund-daily-bills.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:finance:view")
    @GetMapping("/finance/margin-locks")
    public ApiResponse<List<FinanceMarginLockDto>> marginLocks(
            HttpServletRequest request,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return ApiResponse.ok(fundBillService.listMarginLocks(operatorId(request), fromDate, toDate));
    }

    @RequiresPermissions("ops:finance:view")
    @PostMapping("/finance/margin-locks/solidify")
    public ApiResponse<FinanceMarginLockDto> solidifyMargin(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(fundBillService.solidifyMargin(operatorId(request), bizDate));
    }

    // ---- M2 ----

    @RequiresPermissions("ops:rbac:assign")
    @GetMapping("/rbac/users/{userId}/devices")
    public ApiResponse<OpsUserDeviceScopeDto> userDevices(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(gapService.getUserDeviceScope(operatorId(request), userId));
    }

    @RequiresPermissions("ops:rbac:assign")
    @PutMapping("/rbac/users/{userId}/devices")
    public ApiResponse<OpsUserDeviceScopeDto> assignDevices(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody OpsUserDeviceScopeDto body) {
        return ApiResponse.ok(gapService.assignUserDeviceScope(operatorId(request), userId, body));
    }

    @RequiresPermissions("ops:merchant:list")
    @GetMapping("/merchants/{merchantId}/ops-config")
    public ApiResponse<MerchantOpsConfigDto> getOpsConfig(HttpServletRequest request, @PathVariable String merchantId) {
        return ApiResponse.ok(gapService.getOpsConfig(operatorId(request), merchantId));
    }

    @RequiresPermissions("ops:merchant:edit")
    @PutMapping("/merchants/{merchantId}/ops-config")
    public ApiResponse<MerchantOpsConfigDto> saveOpsConfig(
            HttpServletRequest request,
            @PathVariable String merchantId,
            @RequestBody MerchantOpsConfigDto body) {
        return ApiResponse.ok(gapService.saveOpsConfig(operatorId(request), merchantId, body));
    }

    @RequiresPermissions("ops:merchant:list")
    @GetMapping("/merchant-role-templates")
    public ApiResponse<List<MerchantRoleTemplateDto>> roleTemplates(HttpServletRequest request) {
        return ApiResponse.ok(gapService.listRoleTemplates(operatorId(request)));
    }

    // ---- M3 ----

    @RequiresPermissions("ops:device-ops:list")
    @GetMapping("/device-ops/events")
    public ApiResponse<PageResult<DeviceOpsEventDto>> deviceOpsEvents(
            HttpServletRequest request,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "asc") String sortDir) {
        boolean eventIdAsc = !"desc".equalsIgnoreCase(sortDir);
        return ApiResponse.ok(gapService.listDeviceOpsEvents(operatorId(request), eventType, page, size, eventIdAsc));
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/policy")
    public ApiResponse<DevicePolicyDto> getPolicy(HttpServletRequest request, @PathVariable String deviceId) {
        return ApiResponse.ok(gapService.getDevicePolicy(operatorId(request), deviceId));
    }

    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/policy")
    public ApiResponse<DevicePolicyDto> updatePolicy(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @RequestBody DevicePolicyDto body) {
        return ApiResponse.ok(gapService.updateDevicePolicy(operatorId(request), deviceId, body));
    }

    // ---- M4 ----

    @RequiresPermissions("ops:sales-report:list")
    @GetMapping("/sales-reports")
    public ApiResponse<PageResult<SalesReportRowDto>> salesReports(
            HttpServletRequest request,
            @RequestParam(defaultValue = "PRODUCT") String dim,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(gapService.salesReportPage(
                operatorId(request), dim, fromDate, toDate, deviceId, page, size));
    }

    @RequiresPermissions("ops:sales-report:list")
    @GetMapping(value = "/sales-reports/export", produces = "text/csv")
    public ResponseEntity<byte[]> salesReportsExport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "PRODUCT") String dim,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String deviceId) {
        var rows = gapService.salesReport(operatorId(request), dim, fromDate, toDate, deviceId);
        byte[] body = gapService.salesReportCsv(rows).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sales-reports.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @RequiresPermissions("ops:phone-verify:list")
    @GetMapping("/phone-verify/logs")
    public ApiResponse<PageResult<PhoneVerifyLogDto>> phoneVerifyLogs(
            HttpServletRequest request,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(gapService.listPhoneVerify(operatorId(request), phone, channel, page, size));
    }

    @RequiresPermissions("ops:phone-verify:list")
    @PostMapping("/phone-verify/logs")
    public ApiResponse<PhoneVerifyLogDto> createPhoneVerify(
            HttpServletRequest request,
            @RequestBody PhoneVerifyLogDto body) {
        return ApiResponse.ok(gapService.recordPhoneVerify(operatorId(request), body));
    }

    @RequiresPermissions("ops:phone-verify:list")
    @PutMapping("/phone-verify/logs/{logId}")
    public ApiResponse<PhoneVerifyLogDto> updatePhoneVerify(
            HttpServletRequest request,
            @PathVariable Long logId,
            @RequestBody PhoneVerifyLogDto body) {
        return ApiResponse.ok(gapService.updatePhoneVerify(operatorId(request), logId, body));
    }

    @RequiresPermissions("ops:phone-verify:list")
    @DeleteMapping("/phone-verify/logs/{logId}")
    public ApiResponse<Void> deletePhoneVerify(
            HttpServletRequest request, @PathVariable Long logId) {
        gapService.deletePhoneVerify(operatorId(request), logId);
        return ApiResponse.ok(null);
    }

    // ---- M6 stock health ----

    @RequiresPermissions("ops:stock-health:list")
    @GetMapping("/reports/stock-health")
    public ApiResponse<StockHealthPageDto> stockHealth(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "ALL") String dimension,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String routeCode,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(deviceAssetService.stockHealthPage(
                operatorId(request), dimension, merchantId, routeCode, lifecycleStatus, deviceId, page, size));
    }

    @RequiresPermissions("ops:stock-health:export")
    @GetMapping(value = "/reports/stock-health/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportStockHealth(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "ALL") String dimension,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String routeCode,
            @RequestParam(required = false) String lifecycleStatus) {
        List<StockHealthRowDto> rows = deviceAssetService.stockHealth(
                operatorId(request), dimension, merchantId, routeCode, lifecycleStatus, null);
        StringBuilder sb = new StringBuilder("\uFEFF维度,设备ID,设备名,商户,路线,生命周期,SKU,商品,库存,容量,低库存阈值,缺货率%,断货天数,到期日,更新时间\n");
        for (StockHealthRowDto r : rows) {
            sb.append(csv(r.dimension())).append(',')
                    .append(csv(r.deviceId())).append(',')
                    .append(csv(r.deviceName())).append(',')
                    .append(csv(r.merchantId())).append(',')
                    .append(csv(r.routeCode())).append(',')
                    .append(csv(r.lifecycleStatus())).append(',')
                    .append(csv(r.skuId())).append(',')
                    .append(csv(r.skuName())).append(',')
                    .append(r.quantity()).append(',')
                    .append(r.capacity()).append(',')
                    .append(r.lowThreshold() == null ? "" : r.lowThreshold()).append(',')
                    .append(r.stockoutRatePct()).append(',')
                    .append(r.daysOutOfStock() == null ? "" : r.daysOutOfStock()).append(',')
                    .append(r.expiryDate() == null ? "" : r.expiryDate()).append(',')
                    .append(r.updatedAt() == null ? "" : r.updatedAt()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"stock-health.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    private static String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
