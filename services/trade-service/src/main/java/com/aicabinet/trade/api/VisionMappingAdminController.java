package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.UpsertAliyunMappingRequest;
import com.aicabinet.common.dto.UpsertYoloMappingRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.VisionMappingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/ops/admin/vision-mappings")
public class VisionMappingAdminController {

    private final VisionMappingService visionMappingService;

    public VisionMappingAdminController(VisionMappingService visionMappingService) {
        this.visionMappingService = visionMappingService;
    }

    @RequiresPermissions("ops:vision:list")
    @GetMapping
    public ApiResponse<VisionMappingService.VisionMappingsDto> list(HttpServletRequest request) {
        return ApiResponse.ok(visionMappingService.listMappingsForAdmin(operatorId(request)));
    }

    @RequiresPermissions("ops:vision:edit")
    @PostMapping("/yolo")
    public ApiResponse<VisionMappingService.YoloMappingDto> upsertYolo(
            HttpServletRequest request,
            @Valid @RequestBody UpsertYoloMappingRequest body) {
        return ApiResponse.ok(visionMappingService.upsertYolo(operatorId(request), body));
    }

    @RequiresPermissions("ops:vision:edit")
    @DeleteMapping("/yolo/{className}")
    public ApiResponse<Void> deleteYolo(
            HttpServletRequest request,
            @PathVariable("className") String className) {
        visionMappingService.deleteYolo(operatorId(request), className);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:vision:edit")
    @PostMapping("/aliyun")
    public ApiResponse<VisionMappingService.AliyunMappingDto> upsertAliyun(
            HttpServletRequest request,
            @Valid @RequestBody UpsertAliyunMappingRequest body) {
        return ApiResponse.ok(visionMappingService.upsertAliyun(operatorId(request), body));
    }

    @RequiresPermissions("ops:vision:edit")
    @DeleteMapping("/aliyun/{categoryId}")
    public ApiResponse<Void> deleteAliyun(
            HttpServletRequest request,
            @PathVariable("categoryId") String categoryId) {
        visionMappingService.deleteAliyun(operatorId(request), categoryId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
