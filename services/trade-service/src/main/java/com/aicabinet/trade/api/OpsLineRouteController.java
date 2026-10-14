package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.LineRoute;
import com.aicabinet.trade.domain.LineRouteMember;
import com.aicabinet.trade.service.LineRouteAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 线路组织（V358）：线路可增减归档；成员 MANAGER 线长 / STAFF 补货员（同人可双角色）。
 * 权限沿用补货域既有口径 ops:replenishment:list / ops:replenishment:edit。
 * 总线路负责人多人：sys_config line_route.chief_manager_ids（逗号分隔，随时改指）。
 * 业务逻辑在 {@link LineRouteAdminService}（架构门禁：Controller 禁直调 Mapper）。
 */
@Tag(name = "Ops 线路组织")
@RestController
@RequestMapping("/api/v2/ops/admin/line-routes")
public class OpsLineRouteController {

    private final LineRouteAdminService lineRouteAdminService;

    public OpsLineRouteController(LineRouteAdminService lineRouteAdminService) {
        this.lineRouteAdminService = lineRouteAdminService;
    }

    @Operation(summary = "线路列表（含归档）")
    @GetMapping
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<List<LineRoute>> list() {
        return ApiResponse.ok(lineRouteAdminService.list());
    }

    @Operation(summary = "新建线路")
    @PostMapping
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<LineRoute> create(@RequestBody LineRoute route) {
        route.setId(null);
        route.setStatus("ACTIVE");
        route.setCreatedAt(Instant.now());
        return ApiResponse.ok(lineRouteAdminService.create(route));
    }

    @Operation(summary = "修改线路（名称/线长/备注）")
    @PutMapping("/{routeId}")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> update(@PathVariable Long routeId, @RequestBody LineRoute body) {
        lineRouteAdminService.update(routeId, body);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "归档线路（可增减，不物理删）")
    @PostMapping("/{routeId}/archive")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> archive(@PathVariable Long routeId) {
        lineRouteAdminService.archive(routeId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "线路成员列表（同人可双角色两行）")
    @GetMapping("/{routeId}/members")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<List<LineRouteMember>> members(@PathVariable Long routeId) {
        return ApiResponse.ok(lineRouteAdminService.members(routeId));
    }

    @Operation(summary = "添加/更新成员（同人同角色唯一，存在即更新提成配置）")
    @PutMapping("/{routeId}/members")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> upsertMember(@PathVariable Long routeId,
                                          @RequestBody LineRouteMember member) {
        // 返回形状保持 Void（与既有 openapi types 一致；前端不消费返回体）
        lineRouteAdminService.upsertMember(routeId, member);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{routeId}/members/{memberId}")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> removeMember(@PathVariable Long routeId, @PathVariable Long memberId) {
        lineRouteAdminService.removeMember(routeId, memberId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "岗位月合计（线长销售提成 + 补货员佣金，供财务对账；佣金=已发-退款冲回）")
    @GetMapping("/monthly-summary")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<Map<String, Object>> monthlySummary(@RequestParam("month") String month) {
        return ApiResponse.ok(lineRouteAdminService.monthlySummary(month));
    }

    @Operation(summary = "总线路负责人（多人，可随时改指）")
    @GetMapping("/chief-managers")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<Map<String, Object>> chiefManagers() {
        return ApiResponse.ok(Map.of("raw", lineRouteAdminService.chiefManagersRaw()));
    }
}
