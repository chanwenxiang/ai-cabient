package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 审计写入的兜底口径。🔴 {@code admin_audit_log.operator_id} 是 NOT NULL + FK(user_info)，
 * 系统链路传 null 会让整个事务回滚（2026-11-06 服务器实测：选品复核每天都没落库）⇒ 单点归一到 0。
 */
class AdminAuditServiceTest {

    private final AdminAuditLogMapper repository = mock(AdminAuditLogMapper.class);
    private final AdminAuditService service = new AdminAuditService(repository);

    @Test
    void appendLog_nullOperator_fallsBackToSystemOperator() {
        service.appendLog(null, "SKU_DELIST_REVIEW_RUN", "SKU_DELIST_REVIEW", "-", "days=30,rows=0");

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(repository).save(captor.capture());
        assertEquals(SystemConfigService.SYSTEM_OPERATOR_ID, captor.getValue().getOperatorId().longValue());
    }

    /** 有真实操作人时必须原样保留——兜底不能把运营动作记成「系统」。 */
    @Test
    void appendLog_realOperator_isPreserved() {
        service.appendLog(42L, "SKU_DELIST_DECIDE", "SKU", "SKU-A", "action=DELIST");

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(repository).save(captor.capture());
        assertEquals(42L, captor.getValue().getOperatorId().longValue());
        assertEquals("SKU_DELIST_DECIDE", captor.getValue().getAction());
        assertEquals("SKU-A", captor.getValue().getTargetId());
    }

    @Test
    void appendLog_overlongDetail_isTruncatedTo512() {
        service.appendLog(1L, "X", "Y", "1", "a".repeat(600));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(repository).save(captor.capture());
        assertEquals(512, captor.getValue().getDetail().length());
    }
}
