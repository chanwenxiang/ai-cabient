package com.aicabinet.trade.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用数据管理的服务层：表白名单 + 主键自动识别 + 列校验 + 参数化 SQL + 审计。
 * 排除账号/角色/权限树/审计日志表——这些走各自的管理页，且删错会锁死系统。
 */
@Service
public class AdminDataManageService {

    /** 这些表禁止通过通用通道增删改 */
    private static final Set<String> EXCLUDED_TABLES = Set.of(
            "flyway_schema_history",
            "ops_user", "ops_role", "ops_user_role", "ops_role_permission", "ops_permission",
            "admin_audit_log");

    private final JdbcTemplate jdbc;
    private final AdminAuditService auditService;

    public AdminDataManageService(JdbcTemplate jdbc, AdminAuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    public List<String> listManagedTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' "
                        + "ORDER BY table_name",
                String.class);
        return tables.stream().filter(t -> !EXCLUDED_TABLES.contains(t)).toList();
    }

    @Transactional
    public void deleteRow(Long operatorId, String table, String id) {
        requireManaged(table);
        String pk = primaryKeyOf(table);
        int rows = jdbc.update("DELETE FROM " + table + " WHERE " + pk + " = ?", id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在：" + table + "." + id);
        }
        auditService.appendLog(operatorId, "DATA_DELETE", table.toUpperCase(), id, "pk=" + pk);
    }

    @Transactional
    public int updateRow(Long operatorId, String table, String id, Map<String, Object> columns) {
        requireManaged(table);
        String pk = primaryKeyOf(table);
        Map<String, Object> safe = validatedColumns(table, columns, Set.of(pk));
        if (safe.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可更新的列");
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (Map.Entry<String, Object> e : safe.entrySet()) {
            sql.append(e.getKey()).append(" = ?, ");
            params.add(e.getValue());
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE ").append(pk).append(" = ?");
        params.add(id);
        int rows = jdbc.update(sql.toString(), params.toArray());
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在：" + table + "." + id);
        }
        auditService.appendLog(operatorId, "DATA_UPDATE", table.toUpperCase(), id,
                "columns=" + String.join(",", safe.keySet()));
        return rows;
    }

    @Transactional
    public int insertRow(Long operatorId, String table, Map<String, Object> columns) {
        requireManaged(table);
        Map<String, Object> safe = validatedColumns(table, columns, Set.of());
        if (safe.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可插入的列");
        }
        List<Object> params = new ArrayList<>();
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (Map.Entry<String, Object> e : safe.entrySet()) {
            cols.append(e.getKey()).append(", ");
            marks.append("?, ");
            params.add(e.getValue());
        }
        cols.setLength(cols.length() - 2);
        marks.setLength(marks.length() - 2);
        int rows = jdbc.update(
                "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")",
                params.toArray());
        auditService.appendLog(operatorId, "DATA_INSERT", table.toUpperCase(),
                String.valueOf(safe.values().iterator().next()), "columns=" + String.join(",", safe.keySet()));
        return rows;
    }

    // —— 校验 ————————————————————————————————————————————

    private void requireManaged(String table) {
        if (table == null || !table.matches("[a-z0-9_]+") || EXCLUDED_TABLES.contains(table)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该表不允许通过通用数据管理通道操作");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                Integer.class, table);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "表不存在：" + table);
        }
    }

    private String primaryKeyOf(String table) {
        List<String> pks = jdbc.queryForList(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                        + "WHERE tc.table_schema = current_schema() AND tc.table_name = ? "
                        + "AND tc.constraint_type = 'PRIMARY KEY' ORDER BY kcu.ordinal_position",
                String.class, table);
        if (pks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "该表没有主键，不支持按行操作：" + table);
        }
        return pks.get(0);
    }

    /** 列名必须真实存在（防注入），返回有序副本；排除列（如主键/审计列）单独传入 */
    private Map<String, Object> validatedColumns(String table, Map<String, Object> columns, Set<String> excluded) {
        if (columns == null || columns.isEmpty()) {
            return Map.of();
        }
        List<String> valid = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                String.class, table);
        Set<String> validSet = Set.copyOf(valid);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : columns.entrySet()) {
            String col = e.getKey();
            if (excluded.contains(col)) {
                continue;
            }
            if (!validSet.contains(col)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知列：" + col);
            }
            out.put(col, e.getValue());
        }
        return out;
    }

    /** 外键冲突等数据库约束错误 → 409 可读信息 */
    public ResponseStatusException translateIntegrity(DataIntegrityViolationException e) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "数据库约束拒绝该操作（可能存在关联数据）：" + e.getMostSpecificCause().getMessage());
    }
}
