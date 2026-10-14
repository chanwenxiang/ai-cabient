package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.MachinePayeeBill;
import com.aicabinet.trade.domain.MachinePositionConfig;
import com.aicabinet.trade.mapper.MachinePayeeBillMapper;
import com.aicabinet.trade.mapper.MachinePositionConfigMapper;
import com.aicabinet.trade.service.MachinePayeeBillService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * 机器账单（付场地方，V356）：列表 / 手动出账 / 标记结清 / 点位费用配置。
 * 权限沿用设备域口径（ops:device:list 查看 / ops:device:edit 操作），费用项随时可改。
 */
@Tag(name = "Ops 机器账单（付场地方）")
@RestController
@RequestMapping("/api/v2/ops/admin/machine-payee-bills")
public class OpsMachinePayeeBillController {

    private final MachinePayeeBillMapper billMapper;
    private final MachinePositionConfigMapper configMapper;
    private final MachinePayeeBillService billService;

    public OpsMachinePayeeBillController(MachinePayeeBillMapper billMapper,
                                         MachinePositionConfigMapper configMapper,
                                         MachinePayeeBillService billService) {
        this.billMapper = billMapper;
        this.configMapper = configMapper;
        this.billService = billService;
    }

    @Operation(summary = "账单列表（按账期/状态）")
    @GetMapping
    @RequiresPermissions("ops:device:list")
    public ApiResponse<List<MachinePayeeBill>> list(@RequestParam(required = false) String billMonth,
                                             @RequestParam(required = false) String status) {
        return ApiResponse.ok(billMapper.selectList(Wrappers.<MachinePayeeBill>lambdaQuery()
                .eq(billMonth != null && !billMonth.isBlank(), MachinePayeeBill::getBillMonth, billMonth)
                .eq(status != null && !status.isBlank(), MachinePayeeBill::getStatus, status)
                .orderByDesc(MachinePayeeBill::getBillId)));
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
        MachinePayeeBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账单不存在");
        }
        bill.setStatus("SETTLED");
        bill.setSettledAt(Instant.now());
        billMapper.updateById(bill);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "点位费用配置（按柜）")
    @GetMapping("/config/{deviceId}")
    @RequiresPermissions("ops:device:list")
    public ApiResponse<MachinePositionConfig> config(@PathVariable String deviceId) {
        MachinePositionConfig cfg = configMapper.selectById(deviceId);
        return ApiResponse.ok(cfg);
    }

    @Operation(summary = "保存点位费用配置（费用项随时可改，只影响后续账期）")
    @PutMapping("/config/{deviceId}")
    @RequiresPermissions("ops:device:edit")
    public ApiResponse<Void> saveConfig(@PathVariable String deviceId, @RequestBody MachinePositionConfig cfg) {
        cfg.setDeviceId(deviceId);
        cfg.setUpdatedAt(Instant.now());
        if (configMapper.selectById(deviceId) == null) {
            configMapper.insert(cfg);
        } else {
            configMapper.updateById(cfg);
        }
        return ApiResponse.ok(null);
    }
}
