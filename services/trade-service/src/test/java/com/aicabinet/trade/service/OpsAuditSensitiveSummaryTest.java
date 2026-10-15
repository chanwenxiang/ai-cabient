package com.aicabinet.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.aicabinet.common.dto.AdminAuditSensitiveSummaryDto;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link OpsAuditQueryService#sensitiveSummary} 钉住测试（CB-021④ 敏感操作报表）。
 *
 * <p>钉三件事：①前缀白名单族覆盖（资金/权限/凭据/数据直改都在 SQL 参数里，防后人顺手
 * 删前缀让报表静默漏族）；②与明细页同权限点 {@code ops:audit:list}（CB-021③：日志可见性
 * 本身是权限点——汇总报表是日志的另一种可见形态，不得绕过）；③days clamp 到 90（防全表扫描）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsAuditSensitiveSummaryTest {

    @Mock PermissionService permissionService;
    @Mock AdminAuditLogMapper auditLogRepository;
    @Mock UserInfoMapper userInfoRepository;
    @Mock JdbcTemplate jdbc;

    private OpsAuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new OpsAuditQueryService(permissionService, auditLogRepository, userInfoRepository, jdbc);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new AdminAuditSensitiveSummaryDto("BALANCE_ADJUST", 3, Instant.now())));
    }

    @SuppressWarnings("unchecked")
    private record Captured(String sql, Object[] args) {}

    @SuppressWarnings("unchecked")
    private Captured captureQuery() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
        return new Captured(sqlCaptor.getValue(), argsCaptor.getValue());
    }

    @Test
    void sensitiveSummary_sqlCoversAllSensitiveFamilies() {
        service.sensitiveSummary(9L, 7);

        Captured c = captureQuery();
        assertTrue(c.sql().contains("action LIKE ?"), "谓词必须是 LIKE 占位形式");
        assertEquals(OpsAuditQueryService.SENSITIVE_ACTION_PREFIXES.size(), c.args().length,
                "SQL 谓词数必须与前缀族数一致（漂移=报表漏族）");
        for (String family : List.of("BALANCE_ADJUST", "BALANCE_REFUND", "RECHARGE_REFUND",
                "SPLIT_LEDGER", "PROFIT_SHARING", "SITE_RENT", "DEVICE_RENTAL", "DEVICE_DATA_FEE",
                "DEVICE_MQTT_CREDENTIAL", "DEVICE_HTTP_CREDENTIAL", "DEVICE_REMOTE_OPEN",
                "DEVICE_RESET_HARDWARE", "DEVICE_REGENERATE_ID", "OPS_ROLE", "OPS_PERM",
                "OPS_USER_ROLE", "ORG_NODE_ASSIGN", "APPROVAL_DEF", "CONFIG_", "DATA_")) {
            boolean found = false;
            for (Object p : c.args()) {
                if (p.toString().startsWith(family)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "敏感族缺失：" + family);
        }
        assertTrue(c.sql().contains("GROUP BY action"), "必须按 action 聚合");
        assertTrue(c.sql().contains("90") || c.sql().contains("7"), "必须有时间窗");
    }

    @Test
    void sensitiveSummary_requiresAuditListPermission() {
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "no permission"))
                .when(permissionService).requirePermission(9L, "ops:audit:list");

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.sensitiveSummary(9L, 7));
    }

    @Test
    void sensitiveSummary_daysClampedTo90() {
        service.sensitiveSummary(9L, 365);

        Captured c = captureQuery();
        assertTrue(c.sql().contains("(90 || ' days')"), "days 必须 clamp 到 90（防全表扫描）");
    }
}
