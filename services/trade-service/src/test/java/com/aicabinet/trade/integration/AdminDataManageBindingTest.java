package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.AdminDataManageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用数据管理（{@code /ops/admin/data/**}）的**参数类型绑定**回归判据。
 *
 * <p>🔴 缺陷（2026-09-23 实测）：id 来自 URL、列值来自 JSON，两者都以 Java 侧的值进入
 * {@code JdbcTemplate}；PGJDBC 的 {@code setString} 会把参数**声明成 varchar**，于是与强类型列
 * 相遇时 PG 连算子都找不到 —— {@code bigint = character varying} ⇒ SQLSTATE 42883（class 42）
 * ⇒ {@code BadSqlGrammarException} ⇒ 兜底 500「系统繁忙」。受害面：</p>
 * <ul>
 *   <li>{@code repair_ticket} / {@code user_account}（bigint 主键）—— <b>任何</b> id 都 500，
 *       两个页面的「删除数据」「编辑数据」全坏；varchar 主键的表恰好正常，所以一直没被发现。</li>
 *   <li>任何含 int / bigint / timestamptz / boolean / jsonb 列的表 —— 值必须恰好是 JSON 原生类型，
 *       字符串值（金额、时间戳、布尔）一律 500。</li>
 * </ul>
 *
 * <p>修法：按列的**真实类型**给每个占位符加显式 {@code CAST(? AS <udt_name>)}。本测试只认
 * <b>行为语义</b>（HTTP 状态），不绑 SQL 文本 —— 判据要能容忍实现重写：</p>
 * <ul>
 *   <li>合法但不存在的 id ⇒ <b>404</b>（能走到「0 行」分支 = 绑定成功）；</li>
 *   <li>非法 / 超范围 id ⇒ <b>400</b>（可读的输入错误）；</li>
 *   <li>可解析的字符串值 ⇒ 同样走到 <b>404</b>。</li>
 * </ul>
 * <p>全部用「不存在的行」，避免改共享数据。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class AdminDataManageBindingTest {

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

    private static final String MISSING = "9999999999999999999999";

    private static ResponseStatusException thrown(Runnable r) {
        return assertThrows(ResponseStatusException.class, r::run);
    }

    /** 取最内层 SQLException 的 SQLSTATE（用于区分 class 22「数据异常」与 class 42「语法/算子错误」）。 */
    private static String sqlStateOf(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        return root instanceof SQLException se ? String.valueOf(se.getSQLState()) : "";
    }

    // —— bigint 主键：id 绑定 ——

    @Test
    void deleteRow_bigintPk_validMissingId_is404() {
        ResponseStatusException e = thrown(() -> service.deleteRow(1L, "repair_ticket", "999999"));
        assertEquals(
                HttpStatus.NOT_FOUND, e.getStatusCode(), "bigint 主键的 String id 必须能正确绑定（走到 0 行分支）");
    }

    /**
     * 非法 id 必须在**服务边界**以 {@code DataIntegrityViolationException}（class 22）浮出。
     *
     * <p>⚠️ 这里刻意**不**断言 400：{@code translateIntegrity} 是**控制器**的 @ExceptionHandler
     * 调的，服务层只负责抛。若退化成 class 42 的 {@code BadSqlGrammarException}（修复前的
     * 42883），控制器的 handler 根本接不住 ⇒ 兜底 500。故服务层要钉的是「异常类别」。</p>
     */
    @Test
    void deleteRow_bigintPk_nonNumericId_isDataException() {
        DataIntegrityViolationException e =
                assertThrows(DataIntegrityViolationException.class, () -> service.deleteRow(1L, "repair_ticket", "not-a-number"));
        assertTrue(sqlStateOf(e).startsWith("22"), "应为 22xxx（数据异常）；实际 " + sqlStateOf(e));
    }

    @Test
    void deleteRow_bigintPk_outOfRangeId_isDataException() {
        DataIntegrityViolationException e =
                assertThrows(DataIntegrityViolationException.class, () -> service.deleteRow(1L, "repair_ticket", MISSING));
        assertTrue(sqlStateOf(e).startsWith("22"), "应为 22xxx（数据异常）；实际 " + sqlStateOf(e));
    }

    /** 端到端语义串联：服务抛出 → 控制器映射，最终必须是 400 而不是 500。 */
    @Test
    void unparsableId_endsAs400() {
        DataIntegrityViolationException raw = assertThrows(
                DataIntegrityViolationException.class, () -> service.deleteRow(1L, "repair_ticket", "not-a-number"));
        assertEquals(HttpStatus.BAD_REQUEST, service.translateIntegrity(raw).getStatusCode());
    }

    @Test
    void updateRow_bigintPk_validMissingId_is404() {
        ResponseStatusException e =
                thrown(() -> service.updateRow(1L, "repair_ticket", "999999", Map.of("status", "CLOSED")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // —— 强类型列：值绑定（varchar 主键的 cabinet_order 作对照，排除 id 干扰）——

    @Test
    void updateRow_stringValueForIntegerColumn_is404() {
        ResponseStatusException e = thrown(() ->
                service.updateRow(1L, "cabinet_order", "not-exist-order", Map.of("total_amount_cents", "100")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void updateRow_stringValueForTimestampColumn_is404() {
        ResponseStatusException e = thrown(() -> service
                .updateRow(1L, "cabinet_order", "not-exist-order", Map.of("created_at", "2026-01-01T00:00:00Z")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void updateRow_stringValueForBooleanColumn_is404() {
        ResponseStatusException e = thrown(() -> service
                .updateRow(1L, "cabinet_order", "not-exist-order", Map.of("inventory_deducted", "true")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void updateRow_unparsableValue_isMappedToDataIntegrityViolation() {
        // 「值确实无法按列类型解析」是一种 data exception（class 22），必须被 Spring 归类成
        // DataIntegrityViolationException —— 只有这样控制器的 @ExceptionHandler 才接得住并翻成 400。
        // 若退化成 BadSqlGrammarException(class 42)，就会落兜底 500。
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.updateRow(1L, "cabinet_order", "not-exist-order", Map.of("total_amount_cents", "abc")));
    }

    // —— 对照：确认没把原本正常的 varchar 主键路径改坏 ——

    @Test
    void deleteRow_varcharPk_validMissingId_is404() {
        ResponseStatusException e = thrown(() -> service.deleteRow(1L, "ops_exception", "NOPE-NOT-EXIST"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void updateRow_varcharPk_stringValue_is404() {
        ResponseStatusException e = thrown(() ->
                service.updateRow(1L, "ops_exception", "NOPE-NOT-EXIST", Map.of("status", "OPEN")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
