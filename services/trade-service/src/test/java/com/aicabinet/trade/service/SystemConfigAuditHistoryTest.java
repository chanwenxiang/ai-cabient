package com.aicabinet.trade.service;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.domain.SystemConfigHistory;
import com.aicabinet.trade.mapper.SystemConfigHistoryMapper;
import com.aicabinet.trade.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 策略版本与审计（写侧）：{@code SystemConfigService} 的配置变更留痕。
 *
 * <p>守住三条不变式：</p>
 * <ol>
 *   <li><b>默认零留痕</b>：{@code ops.config.audit.enabled} 为 false（或库里没这一行）时，
 *       upsert / delete <b>既不写 version 表也不写审计日志</b> ⇒ 行为与接入前逐字节一致（fail-closed）；</li>
 *   <li><b>开了就真留</b>：oldValue 是变更<b>前</b>的值、newValue 是变更后的值，
 *       而非两次都记成新值（那等于没有版本信息）；</li>
 *   <li><b>无鉴权上下文记系统账号</b>：operatorId 为 null 时落 {@code 0}，不落 null
 *       （列是 NOT NULL，且运营要能看出「这是系统改的」）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemConfigAuditHistoryTest {

    private static final String KEY = "pricing.time_window.discount_percent";

    @Mock private SystemConfigMapper repository;
    @Mock private SystemConfigHistoryMapper historyRepository;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(
                repository,
                historyRepository,
                auditService,
                new SecurityProperties(false),
                new AlipayProperties(false, "", "", "", "", "", "", "", "", "", ""),
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new PayScoreProperties(false, false, 550, false, "", ""),
                new WeChatWebProperties(false, "", ""),
                new WeChatMiniAppProperties(false, "", "", "", "", "", ""),
                new QrProperties("", "", "", "", ""),
                distributedLockService,
                null);
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        // repository.save / historyRepository.save 是接口 default 方法：mock 不会执行其实现，
        // 故显式回显入参，让 toDto 拿到非 null（否则 NPE 会掩盖真正的断言失败）。
        lenient().when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(historyRepository.save(any(SystemConfigHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** 库里某键的现状。 */
    private void existing(String key, String value) {
        SystemConfig config = new SystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setDescription("desc");
        lenient().when(repository.findByIdForUpdate(key)).thenReturn(Optional.of(config));
    }

    /** 审计开关的库中状态：{@code on=false} 时模拟「库里没有这一行」（默认关闭的真实形态）。 */
    private void auditSwitch(boolean on) {
        if (!on) {
            lenient().when(repository.findById(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED))
                    .thenReturn(Optional.empty());
            return;
        }
        SystemConfig flag = new SystemConfig();
        flag.setConfigKey(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED);
        flag.setConfigValue("true");
        lenient().when(repository.findById(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED))
                .thenReturn(Optional.of(flag));
    }

    private SystemConfigHistory capturedHistory() {
        ArgumentCaptor<SystemConfigHistory> captor = ArgumentCaptor.forClass(SystemConfigHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── 不变式 1：默认（关闭）零留痕 ─────────────────────────────────────────

    @Test
    void auditDisabledByDefault_writesNeitherHistoryNorAuditLog() {
        auditSwitch(false);
        existing(KEY, "10");

        service.upsert(KEY, "20", "desc", 9L);

        verify(historyRepository, never()).save(any(SystemConfigHistory.class));
        verify(auditService, never()).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void auditDisabled_deleteAlsoWritesNothing() {
        auditSwitch(false);
        existing(KEY, "10");

        service.delete(KEY, 9L);

        verify(historyRepository, never()).save(any(SystemConfigHistory.class));
        verify(auditService, never()).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    // ── 不变式 2：开启后 old/new 语义正确 ───────────────────────────────────

    @Test
    void auditEnabled_recordsOldValueNewValueAndOperator() {
        auditSwitch(true);
        existing(KEY, "10");

        service.upsert(KEY, "20", "desc", 9L);

        SystemConfigHistory history = capturedHistory();
        assertEquals(KEY, history.getConfigKey());
        assertEquals("10", history.getOldValue(), "oldValue 必须是变更前的值");
        assertEquals("20", history.getNewValue());
        assertEquals(9L, history.getOperatorId().longValue());
        assertNotNull(history.getCreatedAt());
    }

    @Test
    void auditEnabled_firstCreationOfKey_hasNullOldValue() {
        auditSwitch(true);
        lenient().when(repository.findByIdForUpdate("brand.new.key")).thenReturn(Optional.empty());

        service.upsert("brand.new.key", "v1", "desc", 9L);

        SystemConfigHistory history = capturedHistory();
        assertNull(history.getOldValue(), "首次创建没有更早的值，必须为 null（该版本不可回滚）");
        assertEquals("v1", history.getNewValue());
    }

    @Test
    void auditEnabled_delete_recordsNullNewValue() {
        auditSwitch(true);
        existing(KEY, "10");

        service.delete(KEY, 9L);

        SystemConfigHistory history = capturedHistory();
        assertEquals("10", history.getOldValue());
        assertNull(history.getNewValue(), "删除没有「新值」");
    }

    @Test
    void auditEnabled_writesAuditLogWithReadableChangeSummary() {
        auditSwitch(true);
        existing(KEY, "10");

        service.upsert(KEY, "20", "desc", 9L);

        verify(auditService).appendLog(eq(9L), eq(SystemConfigService.CONFIG_ACTION_UPSERT),
                eq(SystemConfigService.CONFIG_AUDIT_TARGET_TYPE), eq(KEY), eq("10 → 20"));
    }

    @Test
    void auditEnabled_deleteAuditLogShowsDeletedMarker() {
        auditSwitch(true);
        existing(KEY, "10");

        service.delete(KEY, 9L);

        verify(auditService).appendLog(eq(9L), eq(SystemConfigService.CONFIG_ACTION_DELETE),
                eq(SystemConfigService.CONFIG_AUDIT_TARGET_TYPE), eq(KEY), eq("10 → （已删除）"));
    }

    // ── 不变式 3：无鉴权上下文记系统账号 ───────────────────────────────────

    @Test
    void nullOperator_isRecordedAsSystemAccountNotNull() {
        auditSwitch(true);
        existing(KEY, "10");

        service.upsert(KEY, "20", "desc", null);

        assertEquals(SystemConfigService.SYSTEM_OPERATOR_ID, capturedHistory().getOperatorId().longValue());
        verify(auditService).appendLog(eq(SystemConfigService.SYSTEM_OPERATOR_ID),
                eq(SystemConfigService.CONFIG_ACTION_UPSERT),
                eq(SystemConfigService.CONFIG_AUDIT_TARGET_TYPE), eq(KEY), anyString());
    }

    // ── 摘要格式（纯函数，直接判） ──────────────────────────────────────────

    @Test
    void describeChange_marksMissingValuesReadably() {
        assertEquals("（未配置） → v1", SystemConfigService.describeChange(null, "v1"));
        assertEquals("v1 → （已删除）", SystemConfigService.describeChange("v1", null));
        assertEquals("a → b", SystemConfigService.describeChange("a", "b"));
    }
}
