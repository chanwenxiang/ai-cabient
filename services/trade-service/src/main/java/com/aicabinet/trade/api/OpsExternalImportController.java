package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ExternalChannelFlowDto;
import com.aicabinet.common.dto.ExternalImportBatchDto;
import com.aicabinet.common.dto.ExternalImportCommitRequest;
import com.aicabinet.common.dto.ExternalImportTemplateDto;
import com.aicabinet.common.dto.ExternalMappingBindRequest;
import com.aicabinet.common.dto.ExternalProductDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ExternalChannelImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 外部渠道对账导入（CB-036，V347；运营台「库存与盘点 → 渠道对账导入」）。
 *
 * <p>权限沿用仓库域既有口径（铁律 57：新能力归属看同类入口落点）：
 * 读 {@code ops:warehouse:list}；写 {@code ops:warehouse:edit} / {@code ops:warehouse:import}。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsExternalImportController {

    private final ExternalChannelImportService importService;

    public OpsExternalImportController(ExternalChannelImportService importService) {
        this.importService = importService;
    }

    // ── 模板与向导 ───────────────────────────────────────────────────────────────────────────

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/external-import/templates")
    public ApiResponse<List<ExternalImportTemplateDto>> templates() {
        return ApiResponse.ok(importService.listTemplates());
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import"},
            logical = RequiresPermissions.Logical.OR)
    @PostMapping(value = "/external-import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ExternalImportBatchDto> preview(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestParam("channelCode") String channelCode,
            @RequestParam("periodLabel") String periodLabel,
            @RequestParam(value = "personOverride", required = false) String personOverride) {
        try {
            return ApiResponse.ok(importService.preview(operatorId(request), channelCode, periodLabel,
                    personOverride, file.getOriginalFilename(), file.getBytes()));
        } catch (IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "文件读取失败");
        }
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import"},
            logical = RequiresPermissions.Logical.OR)
    @PostMapping("/external-import/batches/{batchId}/commit")
    public ApiResponse<ExternalImportBatchDto> commit(@PathVariable Long batchId,
                                                      @RequestBody(required = false) ExternalImportCommitRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(importService.commit(operatorId(request), batchId, body));
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/external-import/batches")
    public ApiResponse<PageResult<ExternalImportBatchDto>> batches(
            @RequestParam(value = "channelCode", required = false) String channelCode,
            @RequestParam(value = "periodLabel", required = false) String periodLabel,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(importService.listBatches(channelCode, periodLabel, page, size));
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/external-import/batches/{batchId}")
    public ApiResponse<ExternalImportBatchDto> batch(@PathVariable Long batchId) {
        return ApiResponse.ok(importService.getBatch(batchId));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import"},
            logical = RequiresPermissions.Logical.OR)
    @DeleteMapping("/external-import/batches/{batchId}")
    public ApiResponse<Void> deleteBatch(@PathVariable Long batchId, HttpServletRequest request) {
        importService.deleteBatch(operatorId(request), batchId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/external-import/flows")
    public ApiResponse<List<ExternalChannelFlowDto>> flows(
            @RequestParam(value = "periodLabel", required = false) String periodLabel,
            @RequestParam(value = "warehouseId", required = false) String warehouseId,
            @RequestParam(value = "opType", required = false) String opType) {
        return ApiResponse.ok(importService.listFlows(periodLabel, warehouseId, opType));
    }

    // ── 外部商品库与映射治理 ─────────────────────────────────────────────────────────────────

    @RequiresPermissions("ops:warehouse:list")
    @GetMapping("/external-products")
    public ApiResponse<PageResult<ExternalProductDto>> products(
            @RequestParam(value = "channelCode", required = false) String channelCode,
            @RequestParam(value = "mapped", required = false) Boolean mapped,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(importService.listProducts(channelCode, mapped, keyword, page, size));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import"},
            logical = RequiresPermissions.Logical.OR)
    @PostMapping("/external-products/{externalProductId}/mapping")
    public ApiResponse<ExternalProductDto> bindMapping(@PathVariable Long externalProductId,
                                                       @RequestBody ExternalMappingBindRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(importService.bindMapping(operatorId(request), externalProductId,
                body.skuId(), body.note()));
    }

    @RequiresPermissions(value = {"ops:warehouse:edit", "ops:warehouse:import"},
            logical = RequiresPermissions.Logical.OR)
    @DeleteMapping("/external-products/{externalProductId}/mapping")
    public ApiResponse<ExternalProductDto> unbindMapping(@PathVariable Long externalProductId,
                                                         HttpServletRequest request) {
        return ApiResponse.ok(importService.unbindMapping(operatorId(request), externalProductId));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
