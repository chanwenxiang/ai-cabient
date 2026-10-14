package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.LineRoute;
import com.aicabinet.trade.domain.LineRouteMember;
import com.aicabinet.trade.mapper.LineRouteMapper;
import com.aicabinet.trade.mapper.LineRouteMemberMapper;
import com.aicabinet.trade.service.SystemConfigService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 线路组织（V358）：线路可增减归档；成员 MANAGER 线长 / STAFF 补货员（同人可双角色）。
 * 权限沿用补货域既有口径 ops:replenishment:list / ops:replenishment:edit。
 * 总线路负责人多人：sys_config line_route.chief_manager_ids（逗号分隔，随时改指）。
 */
@Tag(name = "Ops 线路组织")
@RestController
@RequestMapping("/api/v2/ops/admin/line-routes")
public class OpsLineRouteController {

    private final LineRouteMapper routeMapper;
    private final LineRouteMemberMapper memberMapper;
    private final SystemConfigService systemConfigService;
    private final com.aicabinet.trade.mapper.LineCommissionDailyMapper lineCommissionDailyMapper;
    private final com.aicabinet.trade.mapper.StaffCommissionDailyMapper staffCommissionDailyMapper;

    public OpsLineRouteController(LineRouteMapper routeMapper,
                                  LineRouteMemberMapper memberMapper,
                                  SystemConfigService systemConfigService,
                                  com.aicabinet.trade.mapper.LineCommissionDailyMapper lineCommissionDailyMapper,
                                  com.aicabinet.trade.mapper.StaffCommissionDailyMapper staffCommissionDailyMapper) {
        this.routeMapper = routeMapper;
        this.memberMapper = memberMapper;
        this.systemConfigService = systemConfigService;
        this.lineCommissionDailyMapper = lineCommissionDailyMapper;
        this.staffCommissionDailyMapper = staffCommissionDailyMapper;
    }

    @Operation(summary = "线路列表（含归档）")
    @GetMapping
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<List<LineRoute>> list() {
        return ApiResponse.ok(routeMapper.selectList(
                Wrappers.<LineRoute>lambdaQuery().orderByDesc(LineRoute::getId)));
    }

    @Operation(summary = "新建线路")
    @PostMapping
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<LineRoute> create(@RequestBody LineRoute route) {
        route.setId(null);
        route.setStatus("ACTIVE");
        route.setCreatedAt(Instant.now());
        routeMapper.insert(route);
        return ApiResponse.ok(route);
    }

    @Operation(summary = "修改线路（名称/线长/备注）")
    @PutMapping("/{routeId}")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> update(@PathVariable Long routeId, @RequestBody LineRoute body) {
        LineRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "线路不存在");
        }
        route.setRouteName(body.getRouteName());
        route.setManagerId(body.getManagerId());
        route.setRemark(body.getRemark());
        routeMapper.updateById(route);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "归档线路（可增减，不物理删）")
    @PostMapping("/{routeId}/archive")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> archive(@PathVariable Long routeId) {
        LineRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "线路不存在");
        }
        route.setStatus("ARCHIVED");
        routeMapper.updateById(route);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "线路成员列表（线长+补货员，同一人可双角色）")
    @GetMapping("/{routeId}/members")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<List<LineRouteMember>> members(@PathVariable Long routeId) {
        return ApiResponse.ok(memberMapper.selectList(Wrappers.<LineRouteMember>lambdaQuery()
                .eq(LineRouteMember::getRouteId, routeId)
                .orderByAsc(LineRouteMember::getId)));
    }

    @Operation(summary = "添加/更新成员（role=MANAGER 或 STAFF，含提成配置，可随时改）")
    @PutMapping("/{routeId}/members")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> upsertMember(@PathVariable Long routeId, @RequestBody LineRouteMember member) {
        member.setRouteId(routeId);
        LineRouteMember existing = memberMapper.selectOne(Wrappers.<LineRouteMember>lambdaQuery()
                .eq(LineRouteMember::getRouteId, routeId)
                .eq(LineRouteMember::getUserId, member.getUserId())
                .eq(LineRouteMember::getRole, member.getRole()));
        if (existing == null) {
            member.setId(null);
            member.setStatus("ACTIVE");
            member.setCreatedAt(Instant.now());
            memberMapper.insert(member);
        } else {
            existing.setCommissionBasis(member.getCommissionBasis());
            existing.setCommissionRateBps(member.getCommissionRateBps());
            existing.setCommissionPerUnitCents(member.getCommissionPerUnitCents());
            existing.setStatus(member.getStatus() == null ? "ACTIVE" : member.getStatus());
            memberMapper.updateById(existing);
        }
        return ApiResponse.ok(null);
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{routeId}/members/{memberId}")
    @RequiresPermissions("ops:replenishment:edit")
    public ApiResponse<Void> removeMember(@PathVariable Long routeId, @PathVariable Long memberId) {
        LineRouteMember member = memberMapper.selectById(memberId);
        if (member == null || !routeId.equals(member.getRouteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在");
        }
        memberMapper.deleteById(memberId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "岗位月合计（线长销售提成 + 补货员佣金，供财务对账）")
    @GetMapping("/monthly-summary")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<Map<String, Object>> monthlySummary(@RequestParam("month") String month) {
        java.time.YearMonth ym = java.time.YearMonth.parse(month);
        java.time.LocalDate from = ym.atDay(1);
        java.time.LocalDate to = ym.plusMonths(1).atDay(1);
        // 线长：按人聚合（line_commission_daily：orderCount=订单数、gmvCents=销售额、commissionCents=佣金）
        Map<Long, long[]> managers = new java.util.LinkedHashMap<>();
        for (var row : lineCommissionDailyMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aicabinet.trade.domain.LineCommissionDaily>lambdaQuery()
                        .ge(com.aicabinet.trade.domain.LineCommissionDaily::getBizDate, from)
                        .lt(com.aicabinet.trade.domain.LineCommissionDaily::getBizDate, to))) {
            long[] agg = managers.computeIfAbsent(row.getManagerId(), k -> new long[3]);
            agg[0] += row.getOrderCount() == null ? 0 : row.getOrderCount();
            agg[1] += row.getGmvCents() == null ? 0 : row.getGmvCents();
            agg[2] += row.getCommissionCents() == null ? 0 : row.getCommissionCents();
        }
        // 补货员：按人聚合（staff_commission_daily：restockQty=补货件数、commissionCents=佣金）
        Map<Long, long[]> staffs = new java.util.LinkedHashMap<>();
        for (var row : staffCommissionDailyMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aicabinet.trade.domain.StaffCommissionDaily>lambdaQuery()
                        .ge(com.aicabinet.trade.domain.StaffCommissionDaily::getBizDate, from)
                        .lt(com.aicabinet.trade.domain.StaffCommissionDaily::getBizDate, to))) {
            long[] agg = staffs.computeIfAbsent(row.getStaffUserId(), k -> new long[2]);
            agg[0] += row.getRestockQty() == null ? 0 : row.getRestockQty();
            agg[1] += row.getCommissionCents() == null ? 0 : row.getCommissionCents();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("month", month);
        result.put("managers", managers.entrySet().stream()
                .map(e -> Map.of("managerId", e.getKey(), "orderCount", e.getValue()[0],
                        "gmvCents", e.getValue()[1], "commissionCents", e.getValue()[2]))
                .toList());
        result.put("staffs", staffs.entrySet().stream()
                .map(e -> Map.of("staffUserId", e.getKey(), "restockQty", e.getValue()[0],
                        "commissionCents", e.getValue()[1]))
                .toList());
        return ApiResponse.ok(result);
    }

    @Operation(summary = "总线路负责人（多人，可随时改指）")
    @GetMapping("/chief-managers")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<Map<String, Object>> chiefManagers() {
        String raw = systemConfigService.getValue("line_route.chief_manager_ids", "0");
        return ApiResponse.ok(Map.of("raw", raw == null ? "0" : raw));
    }
}
