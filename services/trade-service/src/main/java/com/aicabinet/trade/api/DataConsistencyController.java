package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.service.DataConsistencyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 运营侧数据一致性巡检 / 显式修复（三端金额、库存对齐）。
 * <p>
 * 响应字段约定（稳定）：
 * <ul>
 *   <li>run → {@code failCount}, {@code failures}</li>
 *   <li>fix → {@code recordId}, {@code fixed}, {@code message}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/ops/admin/consistency")
public class DataConsistencyController {

    private final DataConsistencyService consistencyService;

    public DataConsistencyController(DataConsistencyService consistencyService) {
        this.consistencyService = consistencyService;
    }

    @RequiresPermissions("ops:consistency:list")
    @GetMapping("/failures")
    public ApiResponse<List<DataConsistencyRecord>> failures() {
        return ApiResponse.ok(consistencyService.getFailedChecks());
    }

    @RequiresPermissions("ops:consistency:run")
    @PostMapping("/run")
    public ApiResponse<RunResponse> run() {
        int failCount = consistencyService.runConsistencyCheck();
        List<DataConsistencyRecord> failures = consistencyService.getFailedChecks();
        return ApiResponse.ok(new RunResponse(failCount, failures));
    }

    @RequiresPermissions("ops:consistency:fix")
    @PostMapping("/{recordId}/fix")
    public ApiResponse<FixResponse> fix(@PathVariable Long recordId) {
        DataConsistencyService.FixOutcome outcome = consistencyService.fixInconsistencyDetailed(recordId);
        return ApiResponse.ok(new FixResponse(recordId, outcome.fixed(), outcome.message()));
    }

    public record RunResponse(int failCount, List<DataConsistencyRecord> failures) {}

    public record FixResponse(long recordId, boolean fixed, String message) {}
}
