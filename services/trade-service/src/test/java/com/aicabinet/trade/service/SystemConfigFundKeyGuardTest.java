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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-048：资金阈值键写守卫（值校验 + 强制留痕）与审计开关默认改开。
 * 推翻 F1 旧不变式「默认零留痕」——键族留痕 fail-open、其余键仍可显式关。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemConfigFundKeyGuardTest {

    @Mock private SystemConfigMapper repository;
    @Mock private SystemConfigHistoryMapper historyRepository;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(
                repository, historyRepository, auditService,
                new SecurityProperties(false),
                new AlipayProperties(false, "", "", "", "", "", "", "", "", "", ""),
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new PayScoreProperties(false, false, 550, false, "", ""),
                new WeChatWebProperties(false, "", ""),
                new WeChatMiniAppProperties(false, "", "", "", "", "", ""),
                new QrProperties("", "", "", "", ""),
                distributedLockService, null);
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(historyRepository.save(any(SystemConfigHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findByIdForUpdate(anyString())).thenReturn(Optional.empty());
    }

    /** 库中显式写入审计开关的当前值（区别于「库里没这一行」的默认态）。 */
    private void auditSwitchRow(String value) {
        SystemConfig flag = new SystemConfig();
        flag.setConfigKey(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED);
        flag.setConfigValue(value);
        when(repository.findById(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED)).thenReturn(Optional.of(flag));
    }

    @Test
    void upsert_fundKey_negativeValue_rejected400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(SystemConfigService.RECHARGE_MAX_CENTS, "-1", "d", 9L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_fundKey_nonNumeric_rejected400() {
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(SystemConfigService.REFUND_AUTO_APPROVE_MAX_CENTS, "abc", "d", 9L));
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_fundKey_overflowInt_rejected400() {
        // 读取侧 getInt：>Integer.MAX_VALUE 的值会当场炸读取路径，必须在写侧拒
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(SystemConfigService.MERCHANT_WITHDRAW_DAILY_LIMIT_CENTS,
                        "99999999999", "d", 9L));
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_fundKey_zeroMeansUnlimited_stillAllowed() {
        service.upsert(SystemConfigService.RECHARGE_MAX_CENTS, "0", "d", 9L);
        verify(repository).save(any(SystemConfig.class));
    }

    @Test
    void upsert_fundKey_auditExplicitlyDisabled_stillLeavesHistory() {
        // 攻击形态：先把开关关掉，再改资金键——键族留痕必须不受开关节制
        auditSwitchRow("false");
        service.upsert(SystemConfigService.RECHARGE_MAX_CENTS, "1", "d", 9L);
        verify(historyRepository).save(any(SystemConfigHistory.class));
        verify(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void delete_fundKey_auditExplicitlyDisabled_stillLeavesHistory() {
        // 只钉「留痕不受开关节制」这一不变式；删除动作本身是既有行为，不在本例范围。
        auditSwitchRow("false");
        SystemConfig existing = new SystemConfig();
        existing.setConfigKey(SystemConfigService.MERCHANT_WITHDRAW_MAX_CENTS);
        existing.setConfigValue("1000");
        when(repository.findByIdForUpdate(SystemConfigService.MERCHANT_WITHDRAW_MAX_CENTS))
                .thenReturn(Optional.of(existing));

        service.delete(SystemConfigService.MERCHANT_WITHDRAW_MAX_CENTS, 9L);

        verify(historyRepository).save(any(SystemConfigHistory.class));
        verify(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void upsert_nonFundKey_auditRowAbsent_defaultOn_recordsHistory() {
        // 默认改开的核心断言：库里没有开关行 ⇒ 视为开启（旧设计=视为关⇒零留痕，被 AU-048 推翻）
        when(repository.findById(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED)).thenReturn(Optional.empty());
        service.upsert("pricing.some.flag", "1", "d", 9L);
        verify(historyRepository).save(any(SystemConfigHistory.class));
    }

    @Test
    void upsert_nonFundKey_auditExplicitlyDisabled_writesNothing() {
        auditSwitchRow("false");
        service.upsert("pricing.some.flag", "1", "d", 9L);
        verify(historyRepository, never()).save(any(SystemConfigHistory.class));
    }
}
