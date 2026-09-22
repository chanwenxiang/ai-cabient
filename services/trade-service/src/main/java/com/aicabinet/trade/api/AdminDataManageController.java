package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminDataManageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通用数据管理：给运营后台「清理/订正数据」用的受控 CRUD 通道。
 * - 表白名单（排除账号/角色/权限树/审计日志等），列名经 information_schema 校验，值一律参数化；
 * - 每次操作写 AdminAuditLog（DATA_INSERT/DATA_UPDATE/DATA_DELETE）；
 * - 权限：ops:data:manage（默认仅超管持有，其他角色经角色管理授予）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin/data")
@RequiresPermissions("ops:data:manage")
public class AdminDataManageController {

    private final AdminDataManageService service;

    public AdminDataManageController(AdminDataManageService service) {
        this.service = service;
    }

    @GetMapping("/tables")
    public ApiResponse<List<String>> tables() {
        return ApiResponse.ok(service.listManagedTables());
    }

    @DeleteMapping("/{table}/{id}")
    public ApiResponse<Void> deleteRow(
            HttpServletRequest request,
            @PathVariable String table,
            @PathVariable String id) {
        service.deleteRow(operatorId(request), table, id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{table}/{id}")
    public ApiResponse<Integer> updateRow(
            HttpServletRequest request,
            @PathVariable String table,
            @PathVariable String id,
            @RequestBody Map<String, Object> columns) {
        return ApiResponse.ok(service.updateRow(operatorId(request), table, id, columns));
    }

    @PostMapping("/{table}")
    public ApiResponse<Integer> insertRow(
            HttpServletRequest request,
            @PathVariable String table,
            @RequestBody Map<String, Object> columns) {
        return ApiResponse.ok(service.insertRow(operatorId(request), table, columns));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<Void> onIntegrity(DataIntegrityViolationException e) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(),
                "数据库约束拒绝该操作（存在关联数据）：" + e.getMostSpecificCause().getMessage());
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
