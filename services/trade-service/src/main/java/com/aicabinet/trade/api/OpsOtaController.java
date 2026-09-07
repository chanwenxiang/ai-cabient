package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
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

    private final OpsOtaAdminService otaAdminService;

    public OpsOtaController(OpsOtaAdminService otaAdminService) {
        this.otaAdminService = otaAdminService;
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
        return ApiResponse.ok(otaAdminService.publishOta(operatorId(request), body));
    }

    /** 下架（回滚）：停止推送该版本。 */
    @RequiresPermissions("ops:ota:publish")
    @PostMapping("/ota/releases/{releaseId}/unpublish")
    public ApiResponse<OtaReleaseDto> unpublishOta(
            HttpServletRequest request,
            @PathVariable Long releaseId) {
        return ApiResponse.ok(otaAdminService.unpublishOta(operatorId(request), releaseId));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
