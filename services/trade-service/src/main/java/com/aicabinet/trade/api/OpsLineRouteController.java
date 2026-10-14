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

    public OpsLineRouteController(LineRouteMapper routeMapper,
                                  LineRouteMemberMapper memberMapper,
                                  SystemConfigService systemConfigService) {
        this.routeMapper = routeMapper;
        this.memberMapper = memberMapper;
        this.systemConfigService = systemConfigService;
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

    @Operation(summary = "总线路负责人（多人，可随时改指）")
    @GetMapping("/chief-managers")
    @RequiresPermissions("ops:replenishment:list")
    public ApiResponse<Map<String, Object>> chiefManagers() {
        String raw = systemConfigService.getValue("line_route.chief_manager_ids", "0");
        return ApiResponse.ok(Map.of("raw", raw == null ? "0" : raw));
    }
}
