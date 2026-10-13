package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.OpsExceptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/merchant/exceptions")
public class MerchantOpsExceptionController {
    private final OpsExceptionService service;
    private final MerchantScopeService scopeService;
    private final com.aicabinet.trade.support.MerchantPortalGuard merchantPortalGuard;

    public MerchantOpsExceptionController(OpsExceptionService service, MerchantScopeService scopeService,
            com.aicabinet.trade.support.MerchantPortalGuard merchantPortalGuard) {
        this.service = service;
        this.scopeService = scopeService;
        this.merchantPortalGuard = merchantPortalGuard;
    }

    /**
     * AU-088：先过门户统一闸——「未绑定⇒全局」时 allowedDeviceIds=null，
     * listForDevices 把 null 当全部设备造成跨租户读；requireAccess 拒绝全局态后
     * 该通道不复存在（对照 resolveForMerchant 写侧本就拒 null）。
     */
    @RequiresPermissions(value = {"merchant:alerts:view", "merchant:inventory:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<OpsExceptionDto>> list(HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        merchantPortalGuard.requireAccess(userId);
        var devices = scopeService.allowedDeviceIds(userId);
        return ApiResponse.ok(service.listForDevices(devices, status, page, size));
    }

    /** 库存类异常现场核对结案 */
    @RequiresPermissions("merchant:inventory:view")
    @PostMapping("/{id}/resolve")
    public ApiResponse<OpsExceptionDto> resolve(HttpServletRequest request, @PathVariable String id,
            @Valid @RequestBody ResolveOpsExceptionRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(service.resolveForMerchant(userId, id,
                scopeService.allowedDeviceIds(userId), body.resolution()));
    }
}
