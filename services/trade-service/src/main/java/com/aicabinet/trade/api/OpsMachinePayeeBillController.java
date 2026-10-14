package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.MachinePayeeBill;
import com.aicabinet.trade.domain.MachinePositionConfig;
import com.aicabinet.trade.service.MachinePayeeBillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.util.List;

/**
 * 机器账单（付场地方，V356）：列表 / 手动出账 / 标记结清 / 点位费用配置。
 * 权限沿用设备域口径（ops:device:list 查看 / ops:device:edit 操作），费用项随时可改。
 * 查询/处置逻辑在 {@link MachinePayeeBillService}（架构门禁：Controller 禁直调 Mapper）。
 */
@Tag(name = "Ops 机器账单（付场地方）")
@RestController
@RequestMapping("/api/v2/ops/admin/machine-payee-bills")
public class OpsMachinePayeeBillController {

    private final MachinePayeeBillService billService;

    public OpsMachinePayeeBillController(MachinePayeeBillService billService) {
        this.billService = billService;
    }

    @Operation(summary = "账单列表（按账期/状态）")
    @GetMapping
    @RequiresPermissions("ops:device:list")
    public ApiResponse<List<MachinePayeeBill>> list(@RequestParam(required = false) String billMonth,
                                             @RequestParam(required = false) String status) {
        return ApiResponse.ok(billService.listBills(billMonth, status));
    }

    @Operation(summary = "手动出账（指定账期，幂等）")
    @PostMapping("/generate")
    @RequiresPermissions("ops:device:edit")
    public ApiResponse<Integer> generate(@RequestParam("billMonth") String billMonth) {
        return ApiResponse.ok(billService.generateMonthly(YearMonth.parse(billMonth)));
    }

    @Operation(summary = "标记结清（线下收款后）")
    @PostMapping("/{billId}/settle")
    @RequiresPermissions("ops:device:edit")
    public ApiResponse<Void> settle(@PathVariable Long billId) {
        billService.settle(billId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "点位费用配置（按柜）")
    @GetMapping("/config/{deviceId}")
    @RequiresPermissions("ops:device:list")
    public ApiResponse<MachinePositionConfig> config(@PathVariable String deviceId) {
        return ApiResponse.ok(billService.config(deviceId));
    }

    @Operation(summary = "保存点位费用配置（费用项随时可改，只影响后续账期）")
    @PutMapping("/config/{deviceId}")
    @RequiresPermissions("ops:device:edit")
    public ApiResponse<Void> saveConfig(@PathVariable String deviceId, @RequestBody MachinePositionConfig cfg) {
        billService.saveConfig(deviceId, cfg);
        return ApiResponse.ok(null);
    }
}
