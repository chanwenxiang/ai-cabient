package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.PromotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/promotions")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @RequiresPermissions("ops:promotion:list")
    @GetMapping
    public ApiResponse<List<PromotionActivityDto>> listAll(HttpServletRequest request) {
        return ApiResponse.ok(promotionService.listAll());
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
        return ApiResponse.ok(promotionService.create(body));
    }

    @RequiresPermissions("ops:promotion:edit")
    @PutMapping("/{id}")
    public ApiResponse<PromotionActivityDto> update(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @Valid @RequestBody CreatePromotionRequest body) {
        return ApiResponse.ok(promotionService.update(id, body));
    }

    @RequiresPermissions("ops:promotion:launch")
    @PostMapping("/{id}/launch")
    public ApiResponse<PromotionActivityDto> launch(HttpServletRequest request, @PathVariable("id") Long id) {
        return ApiResponse.ok(promotionService.launch(id));
    }

    @RequiresPermissions("ops:promotion:stop")
    @PostMapping("/{id}/stop")
    public ApiResponse<PromotionActivityDto> stop(HttpServletRequest request, @PathVariable("id") Long id) {
        return ApiResponse.ok(promotionService.stop(id));
    }
}
