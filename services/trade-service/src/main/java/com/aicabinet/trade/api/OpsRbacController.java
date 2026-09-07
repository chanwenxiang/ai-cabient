package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsRbacService;
import com.aicabinet.trade.service.OpsTwoFactorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 运营 RBAC / 个人中心（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsRbacController {

    private final OpsRbacService rbacService;
    private final OpsTwoFactorService opsTwoFactorService;
    private final FileAttachmentService fileAttachmentService;

    public OpsRbacController(OpsRbacService rbacService,
                             OpsTwoFactorService opsTwoFactorService,
                             FileAttachmentService fileAttachmentService) {
        this.rbacService = rbacService;
        this.opsTwoFactorService = opsTwoFactorService;
        this.fileAttachmentService = fileAttachmentService;
    }

    // --- 个人中心：双因子认证（TOTP） ---
    @GetMapping("/rbac/me/two-factor/status")
    public ApiResponse<TwoFactorStatusDto> twoFactorStatus(HttpServletRequest request) {
        return ApiResponse.ok(opsTwoFactorService.status(operatorId(request)));
    }

    @GetMapping("/rbac/me/two-factor/enroll")
    public ApiResponse<TwoFactorEnrollDto> enrollTwoFactor(HttpServletRequest request) {
        return ApiResponse.ok(opsTwoFactorService.enroll(operatorId(request)));
    }

    @PostMapping("/rbac/me/two-factor/confirm")
    public ApiResponse<Void> confirmTwoFactor(HttpServletRequest request,
                                              @Valid @RequestBody TwoFactorCodeRequest body) {
        opsTwoFactorService.confirm(operatorId(request), body.code());
        return ApiResponse.ok(null);
    }

    @PostMapping("/rbac/me/two-factor/disable")
    public ApiResponse<Void> disableTwoFactor(HttpServletRequest request,
                                              @Valid @RequestBody TwoFactorCodeRequest body) {
        opsTwoFactorService.disable(operatorId(request), body.code());
        return ApiResponse.ok(null);
    }

    // --- RBAC（权限以 @RequiresPermissions 为准，增删改注解即可）---
    @RequiresPermissions(value = {"ops:rbac:role", "ops:rbac:assign"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/roles")
    public ApiResponse<List<OpsRoleDto>> roles(HttpServletRequest request) {
        return ApiResponse.ok(rbacService.listRoles(operatorId(request)));
    }

    @RequiresPermissions("ops:rbac:role:add")
    @PostMapping("/rbac/roles")
    public ApiResponse<OpsRoleDto> createRole(
            HttpServletRequest request,
            @Valid @RequestBody CreateOpsRoleRequest body) {
        return ApiResponse.ok(rbacService.createRole(operatorId(request), body));
    }

    @RequiresPermissions("ops:rbac:role:edit")
    @PutMapping("/rbac/roles/{roleId}")
    public ApiResponse<OpsRoleDto> updateRole(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @Valid @RequestBody UpdateOpsRoleRequest body) {
        return ApiResponse.ok(rbacService.updateRole(operatorId(request), roleId, body));
    }

    @RequiresPermissions(value = {"ops:rbac:role", "ops:rbac:menu"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/permissions")
    public ApiResponse<List<OpsPermissionDto>> permissions(
            HttpServletRequest request,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(rbacService.listPermissions(operatorId(request), includeInactive));
    }

    @RequiresPermissions("ops:rbac:menu:add")
    @PostMapping("/rbac/permissions")
    public ApiResponse<OpsPermissionDto> createPermission(
            HttpServletRequest request,
            @Valid @RequestBody CreateOpsPermissionRequest body) {
        return ApiResponse.ok(rbacService.createPermission(operatorId(request), body));
    }

    @RequiresPermissions("ops:rbac:menu:edit")
    @PutMapping("/rbac/permissions/{permissionId}")
    public ApiResponse<OpsPermissionDto> updatePermission(
            HttpServletRequest request,
            @PathVariable Long permissionId,
            @Valid @RequestBody UpdateOpsPermissionRequest body) {
        return ApiResponse.ok(rbacService.updatePermission(operatorId(request), permissionId, body));
    }

    @RequiresPermissions("ops:rbac:menu:remove")
    @DeleteMapping("/rbac/permissions/{permissionId}")
    public ApiResponse<Void> deletePermission(
            HttpServletRequest request,
            @PathVariable Long permissionId) {
        rbacService.deletePermission(operatorId(request), permissionId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions(value = {"ops:rbac:role", "ops:rbac:role:perm"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> rolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId) {
        return ApiResponse.ok(rbacService.getRolePermissions(operatorId(request), roleId));
    }

    @RequiresPermissions("ops:rbac:role:perm")
    @PutMapping("/rbac/roles/{roleId}/permissions")
    public ApiResponse<OpsRolePermissionsDto> assignRolePermissions(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @RequestBody List<Long> permissionIds) {
        return ApiResponse.ok(rbacService.assignRolePermissions(operatorId(request), roleId, permissionIds));
    }

    @RequiresPermissions(value = {"ops:rbac:assign", "ops:replenishment:edit"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/rbac/operators")
    public ApiResponse<PageResult<OpsOperatorDto>> operators(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "phone", required = false) String phone) {
        return ApiResponse.ok(rbacService.listOperators(operatorId(request), page, size, phone));
    }

    @RequiresPermissions("ops:rbac:assign:add")
    @PostMapping("/rbac/operators")
    public ApiResponse<OpsOperatorDto> createOperator(
            HttpServletRequest request,
            @Valid @RequestBody CreateOpsOperatorRequest body) {
        return ApiResponse.ok(rbacService.createOperator(operatorId(request), body));
    }

    @RequiresPermissions("ops:rbac:assign:edit")
    @PutMapping("/rbac/operators/{userId}")
    public ApiResponse<OpsOperatorDto> updateOperator(
            HttpServletRequest request,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateOpsOperatorRequest body) {
        return ApiResponse.ok(rbacService.updateOperator(operatorId(request), userId, body));
    }

    @RequiresPermissions("ops:rbac:assign:disable")
    @DeleteMapping("/rbac/operators/{userId}")
    public ApiResponse<Void> disableOperator(HttpServletRequest request, @PathVariable Long userId) {
        rbacService.disableOperator(operatorId(request), userId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:rbac:assign")
    @GetMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> userRoles(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(rbacService.getUserRoles(operatorId(request), userId));
    }

    @RequiresPermissions("ops:rbac:assign:role")
    @PutMapping("/rbac/users/{userId}/roles")
    public ApiResponse<OpsUserRolesDto> assignRoles(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<Long> roleIds) {
        return ApiResponse.ok(rbacService.assignRoles(operatorId(request), userId, roleIds));
    }

    @RequiresPermissions("ops:rbac:assign")
    @GetMapping("/rbac/users/{userId}/merchants")
    public ApiResponse<OpsUserMerchantsDto> userMerchants(HttpServletRequest request, @PathVariable Long userId) {
        return ApiResponse.ok(rbacService.getUserMerchants(operatorId(request), userId));
    }

    @RequiresPermissions("ops:rbac:assign:merchant")
    @PutMapping("/rbac/users/{userId}/merchants")
    public ApiResponse<OpsUserMerchantsDto> assignMerchants(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<String> merchantIds) {
        return ApiResponse.ok(rbacService.assignMerchants(operatorId(request), userId, merchantIds));
    }

    @GetMapping("/rbac/me/permissions")
    public ApiResponse<java.util.Set<String>> myPermissions(HttpServletRequest request) {
        return ApiResponse.ok(rbacService.myPermissions(operatorId(request)));
    }

    /** ACTIVE 菜单/目录权限码，供前端侧栏与路由按停用状态过滤（不受 ops:admin 旁路影响）。 */
    @GetMapping("/rbac/me/nav")
    public ApiResponse<java.util.Set<String>> myActiveNav(HttpServletRequest request) {
        return ApiResponse.ok(rbacService.activeNavPermissions(operatorId(request)));
    }

    @GetMapping("/rbac/me")
    public ApiResponse<OpsMeDto> myProfile(HttpServletRequest request) {
        return ApiResponse.ok(rbacService.myProfile(operatorId(request)));
    }

    /** 个人中心：自助更新姓名 / 手机号 / 邮箱 / 头像。 */
    @PutMapping("/rbac/me")
    public ApiResponse<OpsMeDto> updateMyProfile(
            @Valid @RequestBody UpdateOpsMeRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(rbacService.updateMyProfile(operatorId(request), body));
    }

    /** 个人中心：上传头像 / Logo。 */
    @PostMapping(value = "/rbac/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadMyAvatar(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(fileAttachmentService.uploadOpsAvatar(operatorId(request), file));
    }

    /** 个人中心：自助修改登录密码。 */
    @PutMapping("/rbac/me/password")
    public ApiResponse<Void> changeMyPassword(
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        rbacService.changeMyPassword(operatorId(request), body);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
