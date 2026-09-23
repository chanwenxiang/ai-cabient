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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * 各表的删除能力（表名 → 是否允许删除）。受保护表由 pg_constraint 推导，
     * 前端据此**不渲染**注定失败的删除入口 —— 而不是点了才报错。
     */
    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Boolean>> deleteCapabilities() {
        return ApiResponse.ok(service.deleteCapabilities());
    }

    /**
     * 列元数据（列名 / 类型 / 可空 / 有默认 / 主键）。
     * 前端据此生成「新增数据」的必填列骨架，避免给一个没有任何列名线索的空模板。
     */
    @GetMapping("/schema/{table}")
    public ApiResponse<List<AdminDataManageService.ColumnMeta>> schema(@PathVariable String table) {
        return ApiResponse.ok(service.columnMetadata(table));
    }

    /**
     * 单行原始列值（键 = 数据库列名）。编辑对话框据此预填 —— 保证「看到的键就是能保存的键」。
     * 路径前缀用 /row，与 /{table}/{id} 的 PUT/DELETE 不产生歧义。
     */
    @GetMapping("/row/{table}/{id}")
    public ApiResponse<Map<String, Object>> row(
            @PathVariable String table,
            @PathVariable String id) {
        return ApiResponse.ok(service.rowDetail(table, id));
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
    public ResponseEntity<ApiResponse<Void>> onIntegrity(DataIntegrityViolationException e) {
        ResponseStatusException mapped = service.translateIntegrity(e);
        return ResponseEntity.status(mapped.getStatusCode())
                .body(ApiResponse.error(mapped.getStatusCode().value(), mapped.getReason()));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
