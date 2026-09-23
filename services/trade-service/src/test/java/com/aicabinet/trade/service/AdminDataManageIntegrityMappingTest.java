package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code translateIntegrity} 的**映射**判据：把 PG 的 SQLSTATE 翻成正确的 HTTP 语义。
 *
 * <p>为什么值这个测试：类 22（data exception）是**调用方输入**问题
 * （22P02 文本不合法 / 22003 超范围 / 22007–22008 日期时间格式），必须回 <b>400</b>；
 * 回 409 会让人误以为「约束冲突」，落兜底 500 则更糟。</p>
 *
 * <p>🔴 2026-09-23 修复前的真实链路：非法 id 触发的是 **42883**
 * （{@code operator does not exist: bigint = character varying}，class **42**）⇒
 * {@code BadSqlGrammarException} ⇒ 连这个 handler 都进不来 ⇒ 兜底 500「系统繁忙」。
 * 故本测试只钉「进来之后要映射对」，进不进得来由
 * {@link com.aicabinet.trade.integration.AdminDataManageBindingTest} 钉。</p>
 */
class AdminDataManageIntegrityMappingTest {

    /** translateIntegrity 是纯函数（不碰 jdbc / audit），故用 mock 占位即可。 */
    private final AdminDataManageService service =
            new AdminDataManageService(mock(JdbcTemplate.class), mock(AdminAuditService.class));

    private static DataIntegrityViolationException withSqlState(String state) {
        return new DataIntegrityViolationException("probe", new SQLException("probe", state));
    }

    @Test
    void class22_dataException_mapsTo400() {
        ResponseStatusException e = service.translateIntegrity(withSqlState("22P02"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(), "22P02（文本不合法）是输入问题 ⇒ 400");
        assertEquals(HttpStatus.BAD_REQUEST, service.translateIntegrity(withSqlState("22003")).getStatusCode(),
                "22003（数值超范围）是输入问题 ⇒ 400");
        assertEquals(HttpStatus.BAD_REQUEST, service.translateIntegrity(withSqlState("22007")).getStatusCode(),
                "22007（日期时间格式）是输入问题 ⇒ 400");
    }

    @Test
    void notNullViolation_mapsTo400() {
        assertEquals(HttpStatus.BAD_REQUEST, service.translateIntegrity(withSqlState("23502")).getStatusCode(),
                "缺必填字段是输入问题 ⇒ 400");
    }

    @Test
    void uniqueAndForeignKey_stayConflict() {
        assertEquals(HttpStatus.CONFLICT, service.translateIntegrity(withSqlState("23505")).getStatusCode(),
                "唯一约束冲突 ⇒ 409");
        assertEquals(HttpStatus.CONFLICT, service.translateIntegrity(withSqlState("23503")).getStatusCode(),
                "外键拒绝 ⇒ 409");
    }

    @Test
    void fkViolationDetail_stillNamesCulpritTableAndConstraint() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "probe",
                new SQLException(
                        "ERROR: update or delete on table \"shopping_session\" violates foreign key constraint "
                                + "\"fk_x\" on table \"dispute_ticket\"",
                        "23503"));
        ResponseStatusException mapped = service.translateIntegrity(e);
        assertEquals(HttpStatus.CONFLICT, mapped.getStatusCode());
        String reason = String.valueOf(mapped.getReason());
        assertTrue(
                reason.contains("dispute_ticket") && reason.contains("fk_x"),
                "文案要指出被引用的表与约束，便于定位；实际：" + reason);
    }
}
