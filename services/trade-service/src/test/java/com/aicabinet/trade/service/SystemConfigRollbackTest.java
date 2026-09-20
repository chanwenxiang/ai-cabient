package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.SystemConfigHistoryDto;
import com.aicabinet.trade.domain.SystemConfigHistory;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.SystemConfigHistoryMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 策略版本与审计（读侧 + 回滚）：{@code SystemConfigAuditService}。
 *
 * <p>守住三条不变式：</p>
 * <ol>
 *   <li><b>回滚撤销的是「那一次变更」</b> ⇒ 用该版本的 oldValue 而非 newValue，
 *       否则「回滚」会把值原地写成它已经是的那个值（= 什么都没做）；</li>
 *   <li><b>没有更早的版本不许回滚</b> ⇒ 首次创建那条的 oldValue 为 null，
 *       硬回滚会写入 null 或空串，把配置变成「未配置」—— 必须显式拒绝（400）；</li>
 *   <li><b>越权与跨键都要挡住</b> ⇒ 无 {@code ops:config:list} 不能看历史、
 *       无 {@code ops:config:edit} 不能回滚；用 A 键的历史去回滚 B 键必须 404。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemConfigRollbackTest {

    private static final long OPERATOR = 7L;
    private static final String KEY = "pricing.clearance.discount_percent";

    @Mock private PermissionService permissionService;
    @Mock private SystemConfigHistoryMapper historyRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private SystemConfigService systemConfigService;

    private SystemConfigAuditService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigAuditService(
                permissionService, historyRepository, userInfoRepository, systemConfigService);
        lenient().when(userInfoRepository.findByUserIdIn(any())).thenReturn(List.of());
    }

    private static SystemConfigHistory history(long id, String key, String oldValue, String newValue) {
        SystemConfigHistory row = new SystemConfigHistory();
        row.setHistoryId(id);
        row.setConfigKey(key);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setOperatorId(3L);
        row.setCreatedAt(Instant.parse("2026-09-20T10:00:00Z"));
        return row;
    }

    // ── 不变式 1：回滚到 oldValue ───────────────────────────────────────────

    @Test
    void rollback_restoresTheValueBeforeThatChange_andKeepsDescription() {
        when(historyRepository.findById(5L))
                .thenReturn(Optional.of(history(5L, KEY, "旧值", "被改坏的值")));
        when(systemConfigService.upsert(KEY, "旧值", null, OPERATOR))
                .thenReturn(new SystemConfigDto(KEY, "旧值", "原描述", Instant.now()));

        SystemConfigDto restored = service.rollback(OPERATOR, KEY, 5L);

        assertEquals("旧值", restored.configValue());
        // 描述传 null：回滚只撤销「值」，不该顺手把描述也改掉（doUpsert 里 null 不覆盖）
        verify(systemConfigService).upsert(KEY, "旧值", null, OPERATOR);
    }

    @Test
    void rollback_trimsIncomingKeyBeforeLookup() {
        when(historyRepository.findById(5L))
                .thenReturn(Optional.of(history(5L, KEY, "旧值", "新值")));
        when(systemConfigService.upsert(eq(KEY), anyString(), any(), anyLong()))
                .thenReturn(new SystemConfigDto(KEY, "旧值", "d", Instant.now()));

        service.rollback(OPERATOR, "  " + KEY + "  ", 5L);

        verify(systemConfigService).upsert(eq(KEY), eq("旧值"), any(), eq(OPERATOR));
    }

    // ── 不变式 2：首次创建版本不可回滚 ──────────────────────────────────────

    @Test
    void rollback_firstCreationVersion_isRejectedAndWritesNothing() {
        when(historyRepository.findById(5L)).thenReturn(Optional.of(history(5L, KEY, null, "初值")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.rollback(OPERATOR, KEY, 5L));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(systemConfigService, never()).upsert(anyString(), any(), any(), any());
    }

    // ── 不变式 3：跨键 / 不存在 / 越权 ──────────────────────────────────────

    @Test
    void rollback_historyBelongingToAnotherKey_isNotFound() {
        when(historyRepository.findById(5L))
                .thenReturn(Optional.of(history(5L, "other.key", "a", "b")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.rollback(OPERATOR, KEY, 5L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(systemConfigService, never()).upsert(anyString(), any(), any(), any());
    }

    @Test
    void rollback_unknownHistory_isNotFound() {
        when(historyRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.rollback(OPERATOR, KEY, 99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void rollback_requiresConfigEditPermission() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(permissionService).requirePermission(OPERATOR, "ops:config:edit");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.rollback(OPERATOR, KEY, 5L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(permissionService).requirePermission(OPERATOR, "ops:config:edit");
    }

    // ── 历史查询 ────────────────────────────────────────────────────────────

    @Test
    void listHistory_returnsVersionsWithResolvedOperatorName() {
        when(historyRepository.findByConfigKeyOrderByCreatedAtDesc(KEY, SystemConfigAuditService.HISTORY_LIMIT))
                .thenReturn(List.of(
                        history(5L, KEY, "10", "20"),
                        history(4L, KEY, null, "10")));
        UserInfo operator = new UserInfo();
        operator.setUserId(3L);
        operator.setName("张运营");
        when(userInfoRepository.findByUserIdIn(any())).thenReturn(List.of(operator));

        List<SystemConfigHistoryDto> rows = service.listHistory(OPERATOR, KEY);

        assertEquals(2, rows.size());
        assertEquals("张运营", rows.get(0).operatorName());
        assertEquals("10", rows.get(0).oldValue());
        assertEquals("20", rows.get(0).newValue());
        // 首次创建那一版：oldValue 为 null，界面据此禁用「回滚到此版本」
        assertNull(rows.get(1).oldValue());
    }

    @Test
    void listHistory_unknownOperatorFallsBackToReadableLabel() {
        when(historyRepository.findByConfigKeyOrderByCreatedAtDesc(KEY, SystemConfigAuditService.HISTORY_LIMIT))
                .thenReturn(List.of(history(5L, KEY, "10", "20")));

        List<SystemConfigHistoryDto> rows = service.listHistory(OPERATOR, KEY);

        assertEquals("账号 3", rows.get(0).operatorName(), "查不到用户时不能显示空白，运营要能对上是哪个账号");
    }

    @Test
    void listHistory_systemOperatorShowsAsSystem() {
        SystemConfigHistory row = history(5L, KEY, "10", "20");
        row.setOperatorId(0L);
        when(historyRepository.findByConfigKeyOrderByCreatedAtDesc(KEY, SystemConfigAuditService.HISTORY_LIMIT))
                .thenReturn(List.of(row));

        assertEquals("系统", service.listHistory(OPERATOR, KEY).get(0).operatorName());
    }

    @Test
    void listHistory_requiresConfigListPermission() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(permissionService).requirePermission(OPERATOR, "ops:config:list");

        assertThrows(ResponseStatusException.class, () -> service.listHistory(OPERATOR, KEY));
        verify(permissionService).requirePermission(OPERATOR, "ops:config:list");
    }

    @Test
    void listHistory_blankKeyRejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listHistory(OPERATOR, "   "));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
