package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import jakarta.validation.Valid;
import com.aicabinet.trade.service.PromotionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/promotions")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    public ApiResponse<List<PromotionActivityDto>> listAll() {
        return ApiResponse.ok(promotionService.listAll());
    }

    @GetMapping("/active")
    public ApiResponse<List<PromotionActivityDto>> listActive() {
        return ApiResponse.ok(promotionService.listActive());
    }

    @GetMapping("/running")
    public ApiResponse<List<PromotionActivityDto>> listRunning() {
        return ApiResponse.ok(promotionService.listCurrentlyRunning());
    }

    @PostMapping
    public ApiResponse<PromotionActivityDto> create(@Valid @RequestBody CreatePromotionRequest request) {
        return ApiResponse.ok(promotionService.create(request));
    }

    @PostMapping("/{id}/launch")
    public ApiResponse<PromotionActivityDto> launch(@PathVariable("id") Long id) {
        return ApiResponse.ok(promotionService.launch(id));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<PromotionActivityDto> stop(@PathVariable("id") Long id) {
        return ApiResponse.ok(promotionService.stop(id));
    }
}
