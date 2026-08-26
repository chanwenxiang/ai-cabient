package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.SkuCatalogDto;
import com.aicabinet.common.dto.SkuVisionEnrollmentPipelineDto;
import com.aicabinet.common.dto.SkuVisionEnrollmentRowDto;
import com.aicabinet.common.dto.UpsertSkuVisionEnrollmentRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.SkuVisionEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/ops/admin/sku-vision")
public class SkuVisionEnrollmentController {

    private final SkuVisionEnrollmentService enrollmentService;

    public SkuVisionEnrollmentController(SkuVisionEnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @RequiresPermissions("ops:sku:list")
    @GetMapping
    public ApiResponse<java.util.List<SkuCatalogDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(enrollmentService.listSkusWithVision(operatorId(request)));
    }

    /** 入驻列表（含映射是否生效、模型管线 stub 状态、下一步动作）。 */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/rows")
    public ApiResponse<PageResult<SkuVisionEnrollmentRowDto>> listRows(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "enrollment", required = false) String enrollment,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(enrollmentService.listEnrollmentRowsPage(
                operatorId(request), q, status, enrollment, page, size));
    }

    @RequiresPermissions("ops:sku:list")
    @GetMapping("/pipeline")
    public ApiResponse<SkuVisionEnrollmentPipelineDto> pipeline(HttpServletRequest request) {
        return ApiResponse.ok(enrollmentService.pipelineMeta(operatorId(request)));
    }

    @RequiresPermissions(value = {"ops:sku:edit", "ops:vision:edit"}, logical = RequiresPermissions.Logical.AND)
    @PostMapping("/enroll")
    public ApiResponse<SkuCatalogDto> enroll(
            HttpServletRequest request,
            @Valid @RequestBody UpsertSkuVisionEnrollmentRequest body) {
        return ApiResponse.ok(enrollmentService.enrollSku(operatorId(request), body));
    }

    @RequiresPermissions("ops:vision:edit")
    @PatchMapping("/{skuId}/status")
    public ApiResponse<SkuCatalogDto> updateStatus(
            HttpServletRequest request,
            @PathVariable("skuId") String skuId,
            @RequestParam("status") String status) {
        return ApiResponse.ok(enrollmentService.updateEnrollmentStatus(operatorId(request), skuId, status));
    }

    /** 按 DRAFT→MAPPING→TESTED→PRODUCTION 顺序推进一档。 */
    @RequiresPermissions("ops:vision:edit")
    @PostMapping("/{skuId}/advance")
    public ApiResponse<SkuVisionEnrollmentRowDto> advance(
            HttpServletRequest request,
            @PathVariable("skuId") String skuId) {
        return ApiResponse.ok(enrollmentService.advanceEnrollment(operatorId(request), skuId));
    }

    @RequiresPermissions(value = {"ops:sku:list", "ops:vision:edit"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping(value = "/suggest-class", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<java.util.Map<String, Object>> suggestClassFromImage(
            HttpServletRequest request,
            @RequestParam("skuName") String skuName,
            @RequestPart("image") org.springframework.web.multipart.MultipartFile image) throws Exception {
        operatorId(request);
        return ApiResponse.ok(enrollmentService.suggestClassFromImage(
                image.getBytes(), image.getOriginalFilename(), skuName));
    }

    @RequiresPermissions(value = {"ops:sku:list", "ops:vision:edit"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/suggest-class-name")
    public ApiResponse<SuggestClassDto> suggestClass(@RequestParam("skuName") String skuName) {
        return ApiResponse.ok(new SuggestClassDto(SkuVisionEnrollmentService.suggestClassName(skuName)));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    public record SuggestClassDto(String yoloClassName) {}
}
