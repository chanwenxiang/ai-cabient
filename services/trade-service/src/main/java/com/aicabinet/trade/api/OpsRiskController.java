package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsRiskAdminService;
import com.aicabinet.trade.service.OpsCsvExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 风控事件与黑名单（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsRiskController {

    private final OpsRiskAdminService riskAdminService;
    private final OpsCsvExportService csvExportService;

    public OpsRiskController(OpsRiskAdminService riskAdminService, OpsCsvExportService csvExportService) {
        this.riskAdminService = riskAdminService;
        this.csvExportService = csvExportService;
    }

    // --- 风控 ---
    @RequiresPermissions("ops:risk:list")
    @GetMapping("/risk/events")
    public ApiResponse<PageResult<RiskEventDto>> riskEvents(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(riskAdminService.listRiskEvents(operatorId(request), page, size));
    }

    @RequiresPermissions("ops:risk:export")
    @GetMapping(value = "/risk/events/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportRiskEvents(HttpServletRequest request) {
        byte[] csv = csvExportService.exportRiskEventsCsv(operatorId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"risk-events.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:risk:blacklist")
    @GetMapping("/risk/blacklist")
    public ApiResponse<List<UserBlacklistDto>> blacklist(HttpServletRequest request) {
        return ApiResponse.ok(riskAdminService.listBlacklist(operatorId(request)));
    }

    @RequiresPermissions("ops:risk:export")
    @GetMapping(value = "/risk/blacklist/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportBlacklist(HttpServletRequest request) {
        byte[] csv = csvExportService.exportBlacklistCsv(operatorId(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"risk-blacklist.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:risk:blacklist")
    @PostMapping("/risk/blacklist")
    public ApiResponse<Void> addBlacklist(
            HttpServletRequest request,
            @Valid @RequestBody AddBlacklistRequest body) {
        riskAdminService.addBlacklist(operatorId(request), body.userId(), body.reason(), body.expiresAt());
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:risk:blacklist")
    @DeleteMapping("/risk/blacklist/{userId}")
    public ApiResponse<Void> removeBlacklist(HttpServletRequest request, @PathVariable Long userId) {
        riskAdminService.removeBlacklist(operatorId(request), userId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
