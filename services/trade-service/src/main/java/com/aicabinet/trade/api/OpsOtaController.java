package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.OpsOtaAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OTA 发布（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsOtaController {

    private static final String BIZ_OTA_RELEASE = "OTA_RELEASE";

    private final OpsOtaAdminService otaAdminService;
    private final AdminAuditService auditService;

    public OpsOtaController(OpsOtaAdminService otaAdminService, AdminAuditService auditService) {
        this.otaAdminService = otaAdminService;
        this.auditService = auditService;
    }

    // --- OTA ---
    @RequiresPermissions("ops:ota:list")
    @GetMapping("/ota/releases")
    public ApiResponse<List<OtaReleaseDto>> listOta(HttpServletRequest request) {
        return ApiResponse.ok(otaAdminService.listOta(operatorId(request)));
    }

    @RequiresPermissions("ops:ota:publish")
    @PostMapping("/ota/releases")
    public ApiResponse<OtaReleaseDto> publishOta(HttpServletRequest request, @RequestBody OtaReleaseDto body) {
        Long operatorId = operatorId(request);
        OtaReleaseDto released = otaAdminService.publishOta(operatorId, body);
        // MA-115（S9 归因）：OTA 发布是全仓最高危运维动作，发布/下架必须留痕
        auditService.appendLog(operatorId, "OTA_RELEASE_PUBLISH", BIZ_OTA_RELEASE,
                String.valueOf(released.releaseId()),
                "版本=" + released.appVersion() + " 渠道=" + released.channel()
                        + " 强制=" + released.mandatory() + " 灰度=" + released.grayPercent() + "%");
        return ApiResponse.ok(released);
    }

    /** 下架（回滚）：停止推送该版本。 */
    @RequiresPermissions("ops:ota:publish")
    @PostMapping("/ota/releases/{releaseId}/unpublish")
    public ApiResponse<OtaReleaseDto> unpublishOta(
            HttpServletRequest request,
            @PathVariable Long releaseId) {
        Long operatorId = operatorId(request);
        OtaReleaseDto unpublished = otaAdminService.unpublishOta(operatorId, releaseId);
        auditService.appendLog(operatorId, "OTA_RELEASE_UNPUBLISH", BIZ_OTA_RELEASE,
                String.valueOf(releaseId),
                "版本=" + unpublished.appVersion() + " 状态=" + unpublished.status());
        return ApiResponse.ok(unpublished);
    }

    /** 设备升级进度列表（O2）；status 省略即不过滤。 */
    @RequiresPermissions("ops:ota:list")
    @GetMapping("/ota/reports")
    public ApiResponse<List<OtaUpgradeProgressDto>> listOtaReports(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(otaAdminService.listProgress(operatorId(request), status));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
