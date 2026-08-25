package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.AssignDepartmentMembersRequest;
import com.aicabinet.common.dto.OpsDepartmentDto;
import com.aicabinet.common.dto.OpsDepartmentMembersDto;
import com.aicabinet.common.dto.UpsertOpsDepartmentRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DepartmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @RequiresPermissions(value = {"ops:dept:list", "ops:approval:config", "ops:rbac:assign"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<List<OpsDepartmentDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(departmentService.list(operatorId(request)));
    }

    @RequiresPermissions("ops:dept:edit")
    @PostMapping
    public ApiResponse<OpsDepartmentDto> create(
            HttpServletRequest request,
            @Valid @RequestBody UpsertOpsDepartmentRequest body) {
        return ApiResponse.ok(departmentService.upsert(operatorId(request), null, body));
    }

    @RequiresPermissions("ops:dept:edit")
    @PutMapping("/{deptId}")
    public ApiResponse<OpsDepartmentDto> update(
            HttpServletRequest request,
            @PathVariable Long deptId,
            @Valid @RequestBody UpsertOpsDepartmentRequest body) {
        return ApiResponse.ok(departmentService.upsert(operatorId(request), deptId, body));
    }

    @RequiresPermissions(value = {"ops:dept:list", "ops:dept:edit"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{deptId}/members")
    public ApiResponse<OpsDepartmentMembersDto> members(
            HttpServletRequest request,
            @PathVariable Long deptId) {
        return ApiResponse.ok(departmentService.members(operatorId(request), deptId));
    }

    @RequiresPermissions("ops:dept:edit")
    @PutMapping("/{deptId}/members")
    public ApiResponse<OpsDepartmentMembersDto> assignMembers(
            HttpServletRequest request,
            @PathVariable Long deptId,
            @RequestBody AssignDepartmentMembersRequest body) {
        return ApiResponse.ok(departmentService.assignMembers(operatorId(request), deptId, body));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
