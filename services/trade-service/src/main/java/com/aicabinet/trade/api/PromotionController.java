package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.PermissionService;
import com.aicabinet.trade.service.PromotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/promotions")
public class PromotionController {

    private final PromotionService promotionService;
    private final PermissionService permissionService;

    public PromotionController(PromotionService promotionService, PermissionService permissionService) {
        this.promotionService = promotionService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ApiResponse<List<PromotionActivityDto>> listAll(HttpServletRequest request) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:list");
        return ApiResponse.ok(promotionService.listAll());
    }

    @GetMapping("/active")
    public ApiResponse<List<PromotionActivityDto>> listActive(HttpServletRequest request) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:list");
        return ApiResponse.ok(promotionService.listActive());
    }

    @GetMapping("/running")
    public ApiResponse<List<PromotionActivityDto>> listRunning(HttpServletRequest request) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:list");
        return ApiResponse.ok(promotionService.listCurrentlyRunning());
    }

    @PostMapping
    public ApiResponse<PromotionActivityDto> create(
            HttpServletRequest request,
            @Valid @RequestBody CreatePromotionRequest body) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:create");
        return ApiResponse.ok(promotionService.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<PromotionActivityDto> update(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @Valid @RequestBody CreatePromotionRequest body) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:edit");
        return ApiResponse.ok(promotionService.update(id, body));
    }

    @PostMapping("/{id}/launch")
    public ApiResponse<PromotionActivityDto> launch(HttpServletRequest request, @PathVariable("id") Long id) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:stop");
        return ApiResponse.ok(promotionService.launch(id));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<PromotionActivityDto> stop(HttpServletRequest request, @PathVariable("id") Long id) {
        permissionService.requirePermission(operatorId(request), "ops:promotion:stop");
        return ApiResponse.ok(promotionService.stop(id));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
