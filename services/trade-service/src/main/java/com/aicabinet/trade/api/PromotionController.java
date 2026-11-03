package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.PromotionPlacementService;
import com.aicabinet.trade.service.PromotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/promotions")
public class PromotionController {

    private final PromotionService promotionService;
    private final PromotionPlacementService placementService;

    public PromotionController(PromotionService promotionService,
                               PromotionPlacementService placementService) {
        this.promotionService = promotionService;
        this.placementService = placementService;
    }

    /** 活动展示位（CB-082）：banner 必选，开门前/关门后弹窗二选一。 */
    @RequiresPermissions("ops:promotion:list")
    @GetMapping("/{id}/placements")
    public ApiResponse<List<PromotionPlacementDto>> placements(
            HttpServletRequest request, @PathVariable("id") Long id) {
        return ApiResponse.ok(placementService.list(id));
    }

    @RequiresPermissions("ops:promotion:edit")
    @PutMapping("/{id}/placements")
    public ApiResponse<List<PromotionPlacementDto>> replacePlacements(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @RequestBody List<UpsertPromotionPlacementRequest> items) {
        return ApiResponse.ok(placementService.replace(operatorId(request), id, items));
    }

    @RequiresPermissions("ops:promotion:list")
    @GetMapping
    public ApiResponse<PageResult<PromotionActivityDto>> listAll(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(promotionService.listPage(q, status, page, size));
    }

    @RequiresPermissions("ops:promotion:list")
    @GetMapping("/active")
    public ApiResponse<List<PromotionActivityDto>> listActive(HttpServletRequest request) {
        return ApiResponse.ok(promotionService.listActive());
    }

    @RequiresPermissions("ops:promotion:list")
    @GetMapping("/running")
    public ApiResponse<List<PromotionActivityDto>> listRunning(HttpServletRequest request) {
        return ApiResponse.ok(promotionService.listCurrentlyRunning());
    }

    @RequiresPermissions(value = {"ops:promotion:create", "ops:promotion:import"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping
    public ApiResponse<PromotionActivityDto> create(
            HttpServletRequest request,
            @Valid @RequestBody CreatePromotionRequest body) {
        return ApiResponse.ok(promotionService.create(operatorId(request), body));
    }

    @RequiresPermissions("ops:promotion:edit")
    @PutMapping("/{id}")
    public ApiResponse<PromotionActivityDto> update(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @Valid @RequestBody CreatePromotionRequest body) {
        return ApiResponse.ok(promotionService.update(operatorId(request), id, body));
    }

    @RequiresPermissions("ops:promotion:launch")
    @PostMapping("/{id}/launch")
    public ApiResponse<PromotionActivityDto> launch(HttpServletRequest request, @PathVariable("id") Long id) {
        return ApiResponse.ok(promotionService.launch(operatorId(request), id));
    }

    @RequiresPermissions("ops:promotion:stop")
    @PostMapping("/{id}/stop")
    public ApiResponse<PromotionActivityDto> stop(HttpServletRequest request, @PathVariable("id") Long id) {
        return ApiResponse.ok(promotionService.stop(operatorId(request), id));
    }
    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
