package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.service.DataConsistencyService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营侧数据一致性巡检 / 显式修复（三端金额、库存对齐）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin/consistency")
public class DataConsistencyController {

    private final DataConsistencyService consistencyService;

    public DataConsistencyController(DataConsistencyService consistencyService) {
        this.consistencyService = consistencyService;
    }

    @RequiresPermissions(value = {"ops:orders:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/failures")
    public ApiResponse<List<DataConsistencyRecord>> failures() {
        return ApiResponse.ok(consistencyService.getFailedChecks());
    }

    @RequiresPermissions(value = {"ops:orders:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run() {
        int failCount = consistencyService.runConsistencyCheck();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("failCount", failCount);
        body.put("failures", consistencyService.getFailedChecks());
        return ApiResponse.ok(body);
    }

    @RequiresPermissions("ops:orders:refund")
    @PostMapping("/{recordId}/fix")
    public ApiResponse<Map<String, Object>> fix(@PathVariable Long recordId) {
        boolean fixed = consistencyService.fixInconsistency(recordId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordId", recordId);
        body.put("fixed", fixed);
        return ApiResponse.ok(body);
    }
}
