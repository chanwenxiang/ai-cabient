package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.LinePromoTaskDto;
import com.aicabinet.common.dto.UpsertLinePromoTaskRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.LinePromoTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/line-promo-tasks")
public class LinePromoTaskController {

    private final LinePromoTaskService promoTaskService;

    public LinePromoTaskController(LinePromoTaskService promoTaskService) {
        this.promoTaskService = promoTaskService;
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<List<LinePromoTaskDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(promoTaskService.list(operator(request), managerId, status));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PostMapping
    public ApiResponse<LinePromoTaskDto> create(
            HttpServletRequest request,
            @Valid @RequestBody UpsertLinePromoTaskRequest body) {
        return ApiResponse.ok(promoTaskService.upsert(operator(request), null, body));
    }

    @RequiresPermissions("ops:line-manager:edit")
    @PutMapping("/{taskId}")
    public ApiResponse<LinePromoTaskDto> update(
            HttpServletRequest request,
            @PathVariable Long taskId,
            @Valid @RequestBody UpsertLinePromoTaskRequest body) {
        return ApiResponse.ok(promoTaskService.upsert(operator(request), taskId, body));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
