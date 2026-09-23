package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.AdminDataManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用数据管理「列名 / 键名契约」回归判据（{@code /ops/admin/data/**}）。
 *
 * <p>🔴 缺陷（2026-09-23 实测，用户从「编辑数据」对话框截图发现）：
 * 编辑框预填的是**列表接口 DTO**（camelCase），而写入侧的列校验走
 * {@code information_schema.columns}，只认**数据库列名**（snake_case）：</p>
 * <pre>
 *   PUT /ops/admin/data/shopping_session/{id}  {"sessionId":"…","openTime":"…"}
 *   → 400 未知列：sessionId
 * </pre>
 * <p>即「编辑数据」对**任何**表都必然失败 —— 入口在、必点必失败，正是「不该有的 / 坏的 CRUD」。
 * 根因不是后端校验错了，而是**前端拿不到数据库列名**（当时没有暴露列元数据 / 单行原始值的端点）。</p>
 *
 * <p>修法：新增 {@code GET /schema/{table}}（列元数据）与 {@code GET /row/{table}/{id}}（单行原始列值，
 * 键 = 数据库列名，值取 {@code ::text}）—— 编辑框与新增模板都以 schema 为唯一事实源，
 * 做到「看到的键就是能保存的键」。本测试钉住这个契约：</p>
 * <ul>
 *   <li>{@link #rowDetail_usesDbColumnNames_notApiDtoNames()} —— 键必须是数据库列名；</li>
 *   <li>{@link #rowDetail_keysAreAcceptedByUpdateRow()} —— 键必须被写入侧**全部**接受（链式，防两处各自漂）；</li>
 *   <li>{@link #rowDetail_keysAreSubsetOfColumnMetadata()} —— 两个端点的键集合同源；</li>
 *   <li>{@link #columnMetadata_marksPrimaryKeyRequiredAndDefaulted()} —— 新增模板要能判「必填」；</li>
 *   <li>{@link #rowDetail_bigintPk_validMissingId_is404()} —— 新端点同样要吃住类型绑定（bigint 主键）。</li>
 * </ul>
 *
 * <p>夹具：{@code ops_exception} 的外键列**全可空**，故插一行不需要任何上游数据；
 * 且链式判据刻意用「不存在的 id」—— 列校验先于 SQL 执行，键名若不被接受会得到 400，
 * 键名全对则得到 404，两者可判别，且**不产生任何写入 / 审计**。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class AdminDataManageColumnContractTest {

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private AdminDataManageService service;

    @Autowired
    private JdbcTemplate jdbc;

    /** 受管表（外键列全可空 ⇒ 夹具不依赖任何上游数据） */
    private static final String TABLE = "ops_exception";
    private static final String PK = "exception_id";
    private static final String FIXTURE_ID = "TEST-COLUMN-CONTRACT";

    /**
     * 操作人夹具：写入成功会走 {@code AdminAuditService.appendLog},
     * 而 {@code admin_audit_log.operator_id} 有 FK 到 {@code user_info} ——
     * 不能假定库里有现成账号，自己造一个用完删。
     */
    private static final long OP_ID = 900000001L;

    @AfterEach
    void removeFixture() {
        // 顺序有依赖：审计日志 RESTRICT 引用 user_info，必须先删日志
        jdbc.update("DELETE FROM admin_audit_log WHERE operator_id = ?", OP_ID);
        jdbc.update("DELETE FROM " + TABLE + " WHERE " + PK + " = ?", FIXTURE_ID);
        jdbc.update("DELETE FROM user_info WHERE user_id = ?", OP_ID);
    }

    private void seedOperator() {
        jdbc.update(
                "INSERT INTO user_info (user_id, phone_number) VALUES (?, ?)"
                        + " ON CONFLICT (user_id) DO NOTHING",
                OP_ID, "19900000001");
    }

    private void seedFixture() {
        seedOperator();
        jdbc.update(
                "INSERT INTO " + TABLE + " (exception_id, exception_type, severity, status, title, dedup_key)"
                        + " VALUES (?, 'TEST', 'LOW', 'OPEN', 'column-contract fixture', ?)",
                FIXTURE_ID, FIXTURE_ID);
    }

    private Map<String, AdminDataManageService.ColumnMeta> metaByName() {
        return service.columnMetadata(TABLE).stream()
                .collect(Collectors.toMap(AdminDataManageService.ColumnMeta::name, Function.identity()));
    }

    private static String sqlStateOf(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        return root instanceof SQLException se ? String.valueOf(se.getSQLState()) : "";
    }

    // —— 键名契约 ——

    /**
     * 编辑框预填的键必须是**数据库列名**。写入侧只认 snake_case；返回 DTO 驼峰名
     * ⇒ 保存时每个键都判「未知列」⇒「编辑数据」必然失败。
     */
    @Test
    void rowDetail_usesDbColumnNames_notApiDtoNames() {
        seedFixture();
        Map<String, Object> row = service.rowDetail(TABLE, FIXTURE_ID);

        assertTrue(row.containsKey("exception_id"), "缺少数据库列名 exception_id；实际键：" + row.keySet());
        assertTrue(row.containsKey("created_at"), "缺少数据库列名 created_at；实际键：" + row.keySet());
        // status 是 varchar 列，回读必须是『OPEN』本身（::text 不额外加引号）
        assertEquals("OPEN", row.get("status"), "status 回读值应与写入值一致");

        List<String> camel = row.keySet().stream()
                .filter(k -> k.chars().anyMatch(Character::isUpperCase))
                .toList();
        assertTrue(
                camel.isEmpty(),
                "键名出现驼峰（异常字段名，接口 DTO 口径）——写入侧不认，保存必 400 未知列：" + camel);
    }

    /**
     * **链式判据（核心）**：{@code rowDetail} 的键必须被写入侧**全部**接受。
     *
     * <p>用「不存在的 id」做判别器：列校验（{@code validatedColumns}）先于 SQL 执行，
     * 故键名有任何一个不认识 ⇒ 400；键名全对 ⇒ 0 行 ⇒ 404。两种结果可判别，
     * 且不产生写入与审计日志（404 在 appendLog 之前抛出）。</p>
     */
    @Test
    void rowDetail_keysAreAcceptedByUpdateRow() {
        seedFixture();
        Map<String, Object> row = new HashMap<>(service.rowDetail(TABLE, FIXTURE_ID));
        row.remove(PK); // 主键列由写入侧按设计排除，不参与列校验

        ResponseStatusException e = assertThrows(
                ResponseStatusException.class,
                () -> service.updateRow(1L, TABLE, "NOPE-NOT-EXIST", row));

        assertEquals(
                HttpStatus.NOT_FOUND,
                e.getStatusCode(),
                "rowDetail 的键必须被写入侧全部接受（否则会停在 400）；实际："
                        + e.getStatusCode()
                        + " / "
                        + e.getReason());
    }

    /** 两个端点必须同源：rowDetail 的每个键都要能在列元数据里找到（前端据元数据渲染提示）。 */
    @Test
    void rowDetail_keysAreSubsetOfColumnMetadata() {
        seedFixture();
        Set<String> names = service.columnMetadata(TABLE).stream()
                .map(AdminDataManageService.ColumnMeta::name)
                .collect(Collectors.toSet());
        Set<String> unknown = service.rowDetail(TABLE, FIXTURE_ID).keySet().stream()
                .filter(k -> !names.contains(k))
                .collect(Collectors.toSet());
        assertTrue(unknown.isEmpty(), "rowDetail 的键不在列元数据内（两端点漂了）：" + unknown);
    }

    // —— 列元数据：新增模板要能判「必填」 ——

    /**
     * 「必填」{@code required} 由后端算（NOT NULL 且无默认值且非 identity）。前端据此把必填列
     * 给 {@code null} 占位 —— 若给 0/'' 之类合法默认值，点保存就会顺手插出一条脏行。
     */
    @Test
    void columnMetadata_marksPrimaryKeyRequiredAndDefaulted() {
        Map<String, AdminDataManageService.ColumnMeta> byName = metaByName();

        AdminDataManageService.ColumnMeta pk = byName.get(PK);
        assertNotNull(pk, "缺少主键列元数据：" + PK);
        assertTrue(pk.primaryKey(), PK + " 应标为主键");
        assertFalse(pk.nullable(), PK + " 不可为空");

        AdminDataManageService.ColumnMeta status = byName.get("status");
        assertNotNull(status, "缺少列元数据：status");
        assertFalse(status.primaryKey(), "status 不是主键");
        assertFalse(status.nullable(), "status 不可为空");
        assertFalse(status.hasDefault(), "status 无默认值");
        assertTrue(status.required(), "status 必填");

        AdminDataManageService.ColumnMeta createdAt = byName.get("created_at");
        assertNotNull(createdAt, "缺少列元数据：created_at");
        assertTrue(createdAt.hasDefault(), "created_at 有默认值");
        assertFalse(createdAt.required(), "created_at 有默认值 ⇒ 不必填");
    }

    /**
     * 🔴 回归锁定（2026-09-23 实测踩到）：必填列**必须包含「无默认值的 NOT NULL 主键」**。
     *
     * <p>前端曾自己推导「必填」并判成「主键一律不必填」（默认主键都自增），于是新增模板漏掉
     * {@code exception_id} 这类**应用侧赋值**的 varchar 主键 ⇒ 点保存必 400「缺少必填字段」——
     * 又一个「必点必失败」的入口。故该规则下沉到后端，并由本判据钉住。</p>
     */
    @Test
    void requiredColumns_includeAppAssignedPrimaryKey() {
        List<String> required = service.columnMetadata(TABLE).stream()
                .filter(AdminDataManageService.ColumnMeta::required)
                .map(AdminDataManageService.ColumnMeta::name)
                .toList();
        assertTrue(
                required.contains(PK),
                "必填列必须包含应用侧赋值的主键 " + PK + "（否则新增模板漏主键、保存必 400）；实际：" + required);
    }

    /** 占位符最少的直觉检查：新增模板只要把所有必填列填上，就应当能插进去。 */
    @Test
    void insertRow_withAllRequiredColumns_succeeds() {
        seedOperator();
        assertFalse(
                service.columnMetadata(TABLE).stream().noneMatch(AdminDataManageService.ColumnMeta::required),
                "本判据依赖该表存在必填列");
        int rows = service.insertRow(
                OP_ID,
                TABLE,
                Map.of(
                        PK, FIXTURE_ID,
                        "exception_type", "TEST",
                        "severity", "LOW",
                        "status", "OPEN",
                        "title", "required-columns fixture",
                        "dedup_key", FIXTURE_ID));
        assertEquals(1, rows, "按「必填列清单」填齐后应能插入成功");
        assertTrue(service.rowDetail(TABLE, FIXTURE_ID).containsKey(PK), "插入后应能按主键读回");
    }

    /** 类型也要带出来：提示文案要用，且回写路径依赖「按列真实类型 CAST」。 */
    @Test
    void columnMetadata_carriesPgType() {
        Map<String, AdminDataManageService.ColumnMeta> byName = metaByName();
        assertEquals("varchar", byName.get(PK).type());
        assertEquals("int8", byName.get("user_id").type(), "user_id 是 bigint ⇒ udt_name 应为 int8");
        assertEquals("timestamptz", byName.get("created_at").type());
    }

    // —— 新端点同样要吃住类型绑定（bigint 主键） ——

    @Test
    void rowDetail_bigintPk_validMissingId_is404() {
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> service.rowDetail("repair_ticket", "999999"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode(), "bigint 主键的 String id 必须能正确绑定");
    }

    @Test
    void rowDetail_bigintPk_nonNumericId_isDataException() {
        DataIntegrityViolationException e = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.rowDetail("repair_ticket", "not-a-number"));
        assertTrue(sqlStateOf(e).startsWith("22"), "应为 22xxx（数据异常）；实际 " + sqlStateOf(e));
    }

    // —— 新端点也必须过表白名单 ——

    @Test
    void newEndpoints_rejectUnmanagedTable() {
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.rowDetail("ops_user", "1")).getStatusCode());
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.columnMetadata("ops_user")).getStatusCode());
    }

    // —— 排除规则：库内 schema 侧的双向扫（2026-09-23 补） ——————————————————————

    /** {@code ops_} 前缀但确属业务表、运营经通用通道订正属正常（与策略单测同一份口径）。 */
    private static final Set<String> BUSINESS_OPS_TABLES = Set.of(
            "ops_department", "ops_device_org", "ops_exception", "ops_org_node");

    /**
     * 修复前**真实漏掉**的表（走遍了 {@code requireManaged} 的放行路径）。
     *
     * <p>{@code ops_2fa_recovery_code} 是其中最重的一条：改 {@code code_hash} 即绕过两步验证；
     * {@code user_account} 改 {@code balance_cents} 即无校验改余额；
     * {@code system_config} 里有 {@code balance.refund.max_cents} 这类资金风控参数。</p>
     */
    private static final List<String> PREVIOUSLY_LEAKED = List.of(
            "ops_user_department",
            "ops_user_device_scope",
            "ops_user_device_scope_pref",
            "ops_user_merchant",
            "ops_user_route_scope",
            "ops_2fa_recovery_code",
            "sys_oper_log",
            "system_config",
            "user_account");

    /** 端到端语义：这些表必须被服务边界挡在 FORBIDDEN，而不是「能进去只是删不掉」。 */
    @Test
    void previouslyLeakedTables_areRejectedAtServiceBoundary() {
        for (String table : PREVIOUSLY_LEAKED) {
            ResponseStatusException e = assertThrows(
                    ResponseStatusException.class,
                    () -> service.rowDetail(table, "1"),
                    table + " 本应被拒绝，却走进了通用数据管理通道");
            assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode(), table + " 应回 403（不可经通用通道操作）");
        }
    }

    /**
     * 🔴 **库内双向扫（自动跟随）**：{@code information_schema} 里所有 {@code ops_} 表必须二选一 ——
     * 被排除规则挡住（⇒ 不出现在受管清单里），或登记在 {@link #BUSINESS_OPS_TABLES}。
     *
     * <p>与策略单测的源码侧扫描互补：那条扫的是 {@code @TableName} 实体，这条扫的是**真库**，
     * 能抓到「迁移里建了表、但没写 JPA 实体」的新增敏感表 —— 那正是最容易被漏保护的一类。</p>
     *
     * <p>⚠️ 自检 {@code opsTables.size() >= 10}：扫描语句若失效（零发现），下面断言恒真全绿。</p>
     */
    @Test
    void everyOpsTableInSchema_isEitherExcludedOrKnownBusinessTable() {
        List<String> opsTables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'"
                        + " AND table_name LIKE 'ops\\_%' ORDER BY table_name",
                String.class);
        assertTrue(
                opsTables.size() >= 10,
                "只扫到 " + opsTables.size() + " 张 ops_ 表（预期 ≥10）—— 扫描语句已失效，本判据会恒真全绿");

        Set<String> managed = Set.copyOf(service.listManagedTables());
        List<String> unclassified = opsTables.stream()
                .filter(t -> !BUSINESS_OPS_TABLES.contains(t))
                .filter(managed::contains)
                .sorted()
                .toList();
        assertTrue(
                unclassified.isEmpty(),
                "这些 ops_ 表既没被排除规则挡住（仍出现在受管清单里）、也不属已知业务表 ⇒ 新增体系表却忘了保护："
                        + unclassified);
    }

    /** 反向对账：别把业务表一起收紧了（否则运营的正当订正入口被砍掉，属过度修）。 */
    @Test
    void managedTableList_stillContainsBusinessTables() {
        List<String> managed = service.listManagedTables();
        for (String t : BUSINESS_OPS_TABLES) {
            assertTrue(managed.contains(t), t + " 是业务表，应仍可经通用通道订正");
        }
        assertTrue(managed.contains("cabinet_order"), "cabinet_order 是既有回归判据的夹具表，不能被排除");
        assertTrue(managed.contains("repair_ticket"), "repair_ticket 是既有回归判据的夹具表，不能被排除");
    }

    // —— 列级保护：凭据列「读不回吐、写不生效」（2026-09-23 补） ————————————————

    /**
     * 🔴 {@code user_info} 是**表级可操作**的业务表（运营要订正手机号等），
     * 但同一张表里躺着 {@code password_hash} / {@code totp_secret}，
     * 而 {@code ops_user_role.user_id} 外键指向 {@code user_info} ⇒ **后台管理员账号就在这张表里**。
     *
     * <p>读到 {@code totp_secret} 等于**直接拿到该管理员的一次性密码**（2FA 形同虚设）；
     * 读到 {@code password_hash} 可离线爆破。故「表可操作」**不等于**「列可操作」——
     * 这是与表级排除**正交**的第二道防线。</p>
     */
    @Test
    void rowDetail_neverReturnsCredentialColumns() {
        seedOperator();
        Map<String, Object> row = service.rowDetail("user_info", String.valueOf(OP_ID));

        for (String col : List.of("password_hash", "totp_secret")) {
            assertFalse(
                    row.containsKey(col),
                    "凭据列 " + col + " 被回吐了（读 totp_secret 即等于拿到 2FA 一次性密码）。实际键：" + row.keySet());
        }
        assertTrue(row.containsKey("phone_number"), "业务列应正常回读；实际键：" + row.keySet());
    }

    /** 写侧：凭据列必须以 403 拒绝，且**不能**因为「列表 DTO 里有这个字段」而被放行。 */
    @Test
    void updateRow_rejectsCredentialColumns() {
        seedOperator();
        for (String col : List.of("password_hash", "totp_secret")) {
            ResponseStatusException e = assertThrows(
                    ResponseStatusException.class,
                    () -> service.updateRow(OP_ID, "user_info", String.valueOf(OP_ID), Map.of(col, "hijacked")),
                    "凭据列 " + col + " 必须被拒绝");
            assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode(), "凭据列 " + col + " 应回 403，而非被默默写入");
        }
    }

    /** 插入侧同样要挡：否则可以塞一条带自选 {@code password_hash} 的账号行。 */
    @Test
    void insertRow_rejectsCredentialColumns() {
        seedOperator();
        ResponseStatusException e = assertThrows(
                ResponseStatusException.class,
                () -> service.insertRow(
                        OP_ID, "user_info", Map.of("user_id", 900000002L, "password_hash", "hijacked")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode(), "新增时写入凭据列也必须 403");
    }
}
