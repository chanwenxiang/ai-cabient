package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.OpsCommercialFacade;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsCommercialController {

    private final OpsCommercialFacade facade;

    public OpsCommercialController(OpsCommercialFacade facade) {
        this.facade = facade;
    }

    // --- OTA ---
    @GetMapping("/ota/releases")
    public ApiResponse<List<OtaReleaseDto>> listOta(HttpServletRequest request) {
        return ApiResponse.ok(facade.listOta(operatorId(request)));
    }

    @PostMapping("/ota/releases")
    public ApiResponse<OtaReleaseDto> publishOta(HttpServletRequest request, @RequestBody OtaReleaseDto body) {
        return ApiResponse.ok(facade.publishOta(operatorId(request), body));
    }

    // --- 风控 ---
    @GetMapping("/risk/events")
    public ApiResponse<PageResult<RiskEventDto>> riskEvents(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(facade.listRiskEvents(operatorId(request), page, size));
    }

    @GetMapping("/risk/blacklist")
    public ApiResponse<List<UserBlacklistDto>> blacklist(HttpServletRequest request) {
        return ApiResponse.ok(facade.listBlacklist(operatorId(request)));
    }

    @PostMapping("/risk/blacklist")
    public ApiResponse<Void> addBlacklist(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        String reason = body.get("reason").toString();
        Instant expiresAt = body.get("expiresAt") != null ? Instant.parse(body.get("expiresAt").toString()) : null;
        facade.addBlacklist(operatorId(request), userId, reason, expiresAt);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/risk/blacklist/{userId}")
    public ApiResponse<Void> removeBlacklist(HttpServletRequest request, @PathVariable Long userId) {
        facade.removeBlacklist(operatorId(request), userId);
        return ApiResponse.ok(null);
    }

    // --- 对账 ---
    @GetMapping("/reconciliation")
    public ApiResponse<List<PaymentReconciliationDto>> reconciliation(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(facade.listReconciliation(operatorId(request), from, to));
    }

    @PostMapping("/reconciliation/run")
    public ApiResponse<PaymentReconciliationDto> runReconciliation(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "WECHAT") String channel) {
        return ApiResponse.ok(facade.runReconciliation(operatorId(request), date, channel));
    }

    @GetMapping("/reconciliation/{reconId}")
    public ApiResponse<PaymentReconciliationDetailDto> reconciliationDetail(
            HttpServletRequest request,
            @PathVariable Long reconId) {
        return ApiResponse.ok(facade.getReconciliationDetail(operatorId(request), reconId));
    }

    // --- 补货 ---
    @GetMapping("/inventory")
    public ApiResponse<List<DeviceInventoryDto>> inventory(
            HttpServletRequest request,
            @RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(facade.listInventory(operatorId(request), deviceId));
    }

    @PutMapping("/inventory")
    public ApiResponse<DeviceInventoryDto> upsertInventory(HttpServletRequest request, @RequestBody DeviceInventoryDto body) {
        return ApiResponse.ok(facade.upsertInventory(operatorId(request), body));
    }

    @GetMapping("/replenishment/routes")
    public ApiResponse<List<ReplenishmentRouteDto>> routes(HttpServletRequest request) {
        return ApiResponse.ok(facade.listRoutes(operatorId(request)));
    }

    @PostMapping("/replenishment/plan")
    public ApiResponse<ReplenishmentRouteDto> planRoute(HttpServletRequest request, @RequestBody PlanRouteRequest body) {
        return ApiResponse.ok(facade.planRoute(operatorId(request), body));
    }

    @PostMapping("/replenishment/routes")
    public ApiResponse<ReplenishmentRouteDto> createRoute(HttpServletRequest request, @RequestBody ReplenishmentRouteDto body) {
        return ApiResponse.ok(facade.createRoute(operatorId(request), body));
    }

    @PostMapping("/replenishment/tasks/{taskId}/complete")
    public ApiResponse<ReplenishmentTaskDto> completeTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ApiResponse.ok(facade.completeTask(operatorId(request), taskId));
    }

    @GetMapping("/replenishment/my-tasks")
    public ApiResponse<List<ReplenishmentTaskDto>> myTasks(HttpServletRequest request) {
        return ApiResponse.ok(facade.myReplenishmentTasks(operatorId(request)));
    }

    // --- SLA ---
    @GetMapping("/sla")
    public ApiResponse<SlaMetricsDto> sla(HttpServletRequest request) {
        return ApiResponse.ok(facade.sla(operatorId(request)));
    }

    // --- RBAC ---
    @GetMapping("/rbac/roles")
    public ApiResponse<List<OpsRoleDto>> roles(HttpServletRequest request) {
        return ApiResponse.ok(facade.listRoles(operatorId(request)));
    }

    @GetMapping("/rbac/permissions")
    public ApiResponse<List<OpsPermissionDto>> permissions(HttpServletRequest request) {
        return ApiResponse.ok(facade.listPermissions(operatorId(request)));
    }

    @GetMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> rolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId) {
        return ApiResponse.ok(facade.getRolePermissions(operatorId(request), roleId));
    }

    @PutMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> assignRolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @RequestBody List<Long> permissionIds) {
        return ApiResponse.ok(facade.assignRolePermissions(operatorId(request), roleId, permissionIds));
    }

    @GetMapping("/rbac/operators")
    public ApiResponse<PageResult<OpsOperatorDto>> operators(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "phone", required = false) String phone) {
        return ApiResponse.ok(facade.listOperators(operatorId(request), page, size, phone));
    }

    @GetMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> userRoles(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(facade.getUserRoles(operatorId(request), userId));
    }

    @PutMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> assignRoles(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<Long> roleIds) {
        return ApiResponse.ok(facade.assignRoles(operatorId(request), userId, roleIds));
    }

    @GetMapping("/rbac/me/permissions")
    public ApiResponse<java.util.Set<String>> myPermissions(HttpServletRequest request) {
        return ApiResponse.ok(facade.myPermissions(operatorId(request)));
    }

    @GetMapping("/rbac/me")
    public ApiResponse<OpsMeDto> myProfile(HttpServletRequest request) {
        return ApiResponse.ok(facade.myProfile(operatorId(request)));
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
