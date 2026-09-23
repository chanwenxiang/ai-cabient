package com.aicabinet.trade.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用数据管理的服务层：表白名单 + 主键自动识别 + 列校验 + 参数化 SQL + 审计。
 * 排除账号/角色/权限树/审计日志表——这些走各自的管理页，且删错会锁死系统。
 *
 * <p>🔴 **类型绑定（2026-09-23 修）**：本通道的 id 来自 URL、列值来自 JSON，两者都以
 * Java 侧的值进入 {@link org.springframework.jdbc.core.JdbcTemplate}。PGJDBC 的
 * {@code setString} 会把参数**声明成 varchar**，于是与强类型列相遇时 PG 连算子都找不到：</p>
 * <pre>
 *   DELETE FROM repair_ticket WHERE ticket_id = ?   -- $1 声明为 varchar
 *   → ERROR: operator does not exist: bigint = character varying   (SQLSTATE 42883)
 *   → BadSqlGrammarException → 兜底 500「系统繁忙」
 * </pre>
 * <p>受害面（实测）：
 * <ul>
 *   <li>{@code repair_ticket} / {@code user_account}（bigint 主键）——**任何** id 都 500，
 *       两个页面的「删除数据」「编辑数据」长期不可用；varchar 主键的表恰好正常，所以一直没被发现。</li>
 *   <li>任何含 int / timestamptz / boolean / jsonb 列的表——值必须是 JSON 原生类型才行，
 *       字符串值（金额、时间戳、布尔）一律 500。</li>
 * </ul>
 * <p>修法：按列的**真实类型**给每个占位符加显式 {@code CAST(? AS <udt_name>)}。
 * 类型名取自 {@code information_schema}（单 token，不是用户输入），故不引入注入面；
 * 显式 CAST 让 varchar→bigint / varchar→timestamptz / varchar→bool 等转换成为合法请求。</p>
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

    /** 删除受保护表集合的懒缓存（schema 在运行期不变，算一次即可） */
    private volatile Set<String> deleteProtectedCache;

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

    /**
     * 「删除可能被挡住」的表集合 —— 由 schema 推导，**不手写清单**（手写清单必漂）。
     *
     * <p>判据：某表 T 若其行删除可能被 RESTRICT / NO ACTION 外键拒绝，或 T 删除时会
     * CASCADE 到这样一张表，则 T 不开放删除。</p>
     *
     * <p>例：{@code shopping_session →CASCADE→ cabinet_order}，而 {@code cabinet_order}
     * 被 {@code order_revenue_split(...RESTRICT)} 等挡住 ⇒ 会话删除同样不可靠。</p>
     */
    public Set<String> deleteProtectedTables() {
        Set<String> cached = deleteProtectedCache;
        if (cached != null) {
            return cached;
        }
        List<String> names = jdbc.queryForList(
                "WITH RECURSIVE cascade_edge AS ("
                        + "  SELECT c.confrelid AS parent, c.conrelid AS child FROM pg_constraint c"
                        + "  WHERE c.contype = 'f' AND c.confdeltype = 'c'),"
                        + " blocker AS ("
                        + "  SELECT DISTINCT c.confrelid AS relid FROM pg_constraint c"
                        + "  WHERE c.contype = 'f' AND c.confdeltype IN ('r', 'a')),"
                        + " unsafe(relid) AS ("
                        + "  SELECT relid FROM blocker"
                        + "  UNION"
                        + "  SELECT e.parent FROM cascade_edge e JOIN unsafe u ON u.relid = e.child)"
                        + " SELECT cl.relname FROM unsafe u"
                        + " JOIN pg_class cl ON cl.oid = u.relid"
                        + " JOIN pg_namespace ns ON ns.oid = cl.relnamespace"
                        + " WHERE cl.relkind = 'r' AND ns.nspname = current_schema()",
                String.class);
        Set<String> set = Set.copyOf(names);
        deleteProtectedCache = set;
        return set;
    }

    /** 全部受管表的删除能力（表名 → 是否允许删除），供前端一次性拉取 */
    public Map<String, Boolean> deleteCapabilities() {
        Set<String> protectedTables = deleteProtectedTables();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String t : listManagedTables()) {
            out.put(t, !protectedTables.contains(t));
        }
        return out;
    }

    /**
     * 单表列元数据：列名 + PG 类型 + 可空 / 有默认值（含 identity）/ 主键 / **必填** / **列注释**。
     *
     * <p>{@code required} 由 SQL 直接算（NOT NULL 且无默认值且非 identity），**不由前端推导** ——
     * 这条规则前端自己推过一版，判成「主键一律不必填」，于是新增模板漏掉主键列
     * （{@code exception_id} 这类**应用侧赋值**的 varchar 主键 NOT NULL 无默认），
     * 保存必 400「缺少必填字段」：又造出一个「必点必失败」的入口。</p>
     *
     * <p>{@code comment} 是 PG 列注释（{@code col_description}）。必须带上：
     * 「新增/编辑数据」的表单要给人看，而**列名是给机器看的** —— 实测库里
     * {@code session_id} 注释是「购物会话ID」、{@code state} 是「状态机状态」，
     * 不带注释的表单等于让人照着数据库字典填空，运营根本没法用。
     * 注释可能为空（不是每张表都写了），前端须有回落（退到列名）。</p>
     */
    public record ColumnMeta(
            String name,
            String type,
            boolean nullable,
            boolean hasDefault,
            boolean primaryKey,
            boolean required,
            String comment) {}

    /**
     * 列元数据 —— 供渲染原始表撰写表单与列清单使用。
     * <p>⚠️ 现状（2026-09-23）：前端入口已撤（写原始列「加上去也没用」），
     * 当前仅 API 与集成测试在使用。</p>
     * 一律现查 {@code information_schema} / {@code pg_description}：手写列清单必漂。
     */
    public List<ColumnMeta> columnMetadata(String table) {
        requireManaged(table);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.column_name AS name, c.udt_name AS col_type,"
                        + " (c.is_nullable = 'YES') AS can_be_null,"
                        + " (c.column_default IS NOT NULL OR c.is_identity = 'YES') AS has_default,"
                        + " (pk.column_name IS NOT NULL) AS is_pk,"
                        + " (c.is_nullable = 'NO' AND c.column_default IS NULL"
                        + "  AND c.is_identity = 'NO') AS required,"
                        + " d.description AS col_comment"
                        + " FROM information_schema.columns c"
                        + " LEFT JOIN ("
                        + "   SELECT kcu.column_name FROM information_schema.table_constraints tc"
                        + "   JOIN information_schema.key_column_usage kcu"
                        + "     ON tc.constraint_name = kcu.constraint_name"
                        + "    AND tc.table_schema = kcu.table_schema"
                        + "   WHERE tc.table_schema = current_schema() AND tc.table_name = ?"
                        + "     AND tc.constraint_type = 'PRIMARY KEY'"
                        + " ) pk ON pk.column_name = c.column_name"
                        // 列注释：pg_description 按 (表 oid, 列序号) 存
                        + " LEFT JOIN pg_class cl ON cl.relname = c.table_name"
                        + "   AND cl.relnamespace = current_schema()::regnamespace"
                        + " LEFT JOIN pg_description d ON d.objoid = cl.oid"
                        + "   AND d.objsubid = c.ordinal_position"
                        + " WHERE c.table_schema = current_schema() AND c.table_name = ?"
                        + " ORDER BY c.ordinal_position",
                table, table);
        List<ColumnMeta> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object comment = r.get("col_comment");
            out.add(new ColumnMeta(
                    String.valueOf(r.get("name")),
                    String.valueOf(r.get("col_type")),
                    Boolean.TRUE.equals(r.get("can_be_null")),
                    Boolean.TRUE.equals(r.get("has_default")),
                    Boolean.TRUE.equals(r.get("is_pk")),
                    Boolean.TRUE.equals(r.get("required")),
                    comment == null ? null : String.valueOf(comment)));
        }
        return out;
    }

    /**
     * 单行**原始列值**：列名 = 数据库列名（snake_case），值统一取 {@code ::text}（NULL 原样为 null）。
     *
     * <p>为什么编辑框必须用数据库列名而不是列表接口的 DTO 字段名：{@link #updateRow} 的列校验走
     * {@code information_schema.columns}，只认 snake_case。前端若把 DTO（camelCase）填进编辑框，
     * 保存时每个键都判「未知列」⇒ 400 —— 即「编辑数据」对**任何**表都必然失败
     * （2026-09-23 实测：{@code PUT {sessionId,…}} → {@code 400 未知列：sessionId}）。</p>
     *
     * <p>值取 text 与写入侧对称：写入侧一律 {@code CAST(? AS <udt>)}，故 text 形态可原样回写，
     * 数字 / 时间戳 / 布尔 / jsonb 都不必在两端各写一套转换规则。</p>
     */
    public Map<String, Object> rowDetail(String table, String id) {
        requireManaged(table);
        PkColumn pk = primaryKey(table);
        StringBuilder select = new StringBuilder();
        for (String col : columnTypes(table).keySet()) {
            if (select.length() > 0) {
                select.append(", ");
            }
            select.append(col).append("::text AS ").append(col);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + select + " FROM " + table
                        + " WHERE " + pk.name() + " = " + placeholder(pk.udt()),
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在：" + table + "." + id);
        }
        return rows.get(0);
    }

    @Transactional
    public void deleteRow(Long operatorId, String table, String id) {
        requireManaged(table);
        if (deleteProtectedTables().contains(table)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该表存在可能阻止删除的下游引用，未开放删除：" + table);
        }
        PkColumn pk = primaryKey(table);
        int rows = jdbc.update(
                "DELETE FROM " + table + " WHERE " + pk.name() + " = " + placeholder(pk.udt()), id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在：" + table + "." + id);
        }
        auditService.appendLog(operatorId, "DATA_DELETE", table.toUpperCase(), id, "pk=" + pk.name());
    }

    @Transactional
    public int updateRow(Long operatorId, String table, String id, Map<String, Object> columns) {
        requireManaged(table);
        Map<String, String> types = columnTypes(table);
        PkColumn pk = primaryKey(table);
        Map<String, Object> safe = validatedColumns(types, columns, Set.of(pk.name()));
        if (safe.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可更新的列");
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (Map.Entry<String, Object> e : safe.entrySet()) {
            sql.append(e.getKey()).append(" = ").append(placeholder(types, e.getKey())).append(", ");
            params.add(e.getValue());
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE ").append(pk.name()).append(" = ").append(placeholder(types, pk.name()));
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
        Map<String, String> types = columnTypes(table);
        Map<String, Object> safe = validatedColumns(types, columns, Set.of());
        if (safe.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可插入的列");
        }
        List<Object> params = new ArrayList<>();
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (Map.Entry<String, Object> e : safe.entrySet()) {
            cols.append(e.getKey()).append(", ");
            marks.append(placeholder(types, e.getKey())).append(", ");
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

    /** 主键列：列名 + PG 类型名（{@code udt_name}）。类型名用于给占位符加显式 CAST。 */
    private record PkColumn(String name, String udt) {}

    private PkColumn primaryKey(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kcu.column_name AS name, col.udt_name AS udt "
                        + "FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                        + "JOIN information_schema.columns col "
                        + "ON col.table_schema = kcu.table_schema AND col.table_name = kcu.table_name "
                        + "AND col.column_name = kcu.column_name "
                        + "WHERE tc.table_schema = current_schema() AND tc.table_name = ? "
                        + "AND tc.constraint_type = 'PRIMARY KEY' ORDER BY kcu.ordinal_position",
                table);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "该表没有主键，不支持按行操作：" + table);
        }
        Map<String, Object> row = rows.get(0);
        return new PkColumn(String.valueOf(row.get("name")), String.valueOf(row.get("udt")));
    }

    /** 列名 → PG 类型名（{@code udt_name}），用于给每个占位符加上与该列一致的显式 CAST。 */
    private Map<String, String> columnTypes(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT column_name AS name, udt_name AS udt FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                table);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            out.put(String.valueOf(r.get("name")), String.valueOf(r.get("udt")));
        }
        return out;
    }

    /** 数组类型的 {@code udt_name} 形如 `_int4`，CAST 目标须写作 `int4[]`。 */
    private static String castType(String udt) {
        return udt.startsWith("_") ? udt.substring(1) + "[]" : udt;
    }

    private static String placeholder(String udt) {
        return "CAST(? AS " + castType(udt) + ")";
    }

    private static String placeholder(Map<String, String> types, String column) {
        return placeholder(types.get(column));
    }

    /** 列名必须真实存在（防注入），返回有序副本；排除列（如主键/审计列）单独传入 */
    private Map<String, Object> validatedColumns(
            Map<String, String> types, Map<String, Object> columns, Set<String> excluded) {
        if (columns == null || columns.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : columns.entrySet()) {
            String col = e.getKey();
            if (excluded.contains(col)) {
                continue;
            }
            if (!types.containsKey(col)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知列：" + col);
            }
            out.put(col, e.getValue());
        }
        return out;
    }

    /** 外键违约文案里被引用的约束 / 表（PG 固定形如：constraint "x" on table "y"） */
    private static final Pattern FK_DETAIL = Pattern.compile("constraint \"([^\"]+)\" on table \"([^\"]+)\"");

    /** 外键冲突等数据库约束错误 → 可读 4xx（不回吐 PG 原文，避免泄露内部结构） */
    public ResponseStatusException translateIntegrity(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        String detail = root != null ? String.valueOf(root.getMessage()) : "";
        String state = root instanceof SQLException se ? se.getSQLState() : null;
        Matcher m = FK_DETAIL.matcher(detail);
        if (m.find()) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "存在下游引用，已拒绝该操作（被引用于表 " + m.group(2) + "，约束 " + m.group(1) + "）");
        }
        if (state != null) {
            if (state.startsWith("22")) {
                // 22xxx = data exception：值无法按目标列的类型解析
                // （22P02 文本不合法 / 22003 超范围 / 22007-22008 日期时间格式…）⇒ 调用方的输入问题
                return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "字段值或主键格式不合法：数据库无法按目标列的类型解析该值");
            }
            if ("23502".equals(state)) {
                return new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少必填字段（该列不允许为空）");
            }
            if ("23505".equals(state)) {
                return new ResponseStatusException(HttpStatus.CONFLICT, "唯一约束冲突（该值已存在）");
            }
            if ("23503".equals(state)) {
                return new ResponseStatusException(HttpStatus.CONFLICT, "外键约束拒绝该操作（引用的上游记录不存在）");
            }
        }
        return new ResponseStatusException(HttpStatus.CONFLICT, "数据库约束拒绝该操作（存在违反约束的数据）");
    }
}
