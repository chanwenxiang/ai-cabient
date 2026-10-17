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
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-134/DC-27：结算识别方式的写侧值域闸门。
 *
 * <p>识别口径只有「柜机端侧识别」一种，重力融合属已退役链的残留；本组用例钉的是
 * 「残留不可被配活」，而不是「残留代码已删」——后者属 DC-27(b) 整体删除，届时连同
 * {@link SystemConfigService#usesGravityFusion()} 一起消失。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemConfigRecognitionModeTest {

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
        lenient().when(repository.findById(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        lenient().when(repository.findByIdForUpdate(ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        lenient().when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    /** 库里人为已有值（绕过写侧闸门的历史行）时，读侧仍如实跟随。 */
    private void stubStored(String key, String value) {
        SystemConfig row = new SystemConfig();
        row.setConfigKey(key);
        row.setConfigValue(value);
        when(repository.findById(key)).thenReturn(Optional.of(row));
    }

    @Test
    void recognitionMode_acceptsVision() {
        service.upsert(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "VISION", null);

        verify(repository).save(ArgumentMatchers.argThat(
                c -> SystemConfigService.SETTLEMENT_RECOGNITION_MODE.equals(c.getConfigKey())
                        && "VISION".equals(c.getConfigValue())));
    }

    @Test
    void recognitionMode_acceptsVisionIgnoringCaseAndSurroundingSpace() {
        service.upsert(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "  vision ", null);

        verify(repository).save(ArgumentMatchers.argThat(c -> "  vision ".equals(c.getConfigValue())));
    }

    @Test
    void recognitionMode_rejectsGravityFusionWithZeroWrite() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(
                        SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "VISION_GRAVITY", null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        // 零残留：被拒的写不得留下任何行，也不得释放锁前落库
        verify(repository, never()).save(any(SystemConfig.class));
    }

    @Test
    void recognitionMode_rejectsBlankAndUnknownValues() {
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "", null));
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, null, null));
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "CLOUD_VISION", null));
        verify(repository, never()).save(any(SystemConfig.class));
    }

    @Test
    void gateIsScopedToRecognitionModeOnly() {
        service.upsert(SystemConfigService.CONSUMER_SERVICE_PHONE, "VISION_GRAVITY", null);

        verify(repository).save(ArgumentMatchers.argThat(
                c -> "VISION_GRAVITY".equals(c.getConfigValue())));
    }

    @Test
    void readSideStillFollowsLegacyRowWrittenBeforeTheGate() {
        stubStored(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "VISION_GRAVITY");
        assertTrue(service.usesGravityFusion());

        stubStored(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "VISION");
        assertFalse(service.usesGravityFusion());
    }

    /**
     * DC-27(a) 收尾：本键属「不可静默修改」族——运营把审计开关显式写成 false，
     * 改识别方式仍必须落版本历史 + 审计日志（曾发生过用它凑出结算绿的事故）。
     */
    @Test
    void recognitionMode_forcedAuditEvenWhenAuditSwitchIsOff() {
        stubStored(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED, "false");

        service.upsert(SystemConfigService.SETTLEMENT_RECOGNITION_MODE, "VISION", null, 9L);

        verify(historyRepository).save(any(SystemConfigHistory.class));
        verify(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    /** 对照组：开关关掉后普通键仍零留痕，证明上一条不是恒真断言。 */
    @Test
    void ordinaryKey_staysSilentWhenAuditSwitchIsOff() {
        stubStored(SystemConfigService.OPS_CONFIG_AUDIT_ENABLED, "false");

        service.upsert(SystemConfigService.CONSUMER_SERVICE_PHONE, "4000000000", null, 9L);

        verify(historyRepository, never()).save(any(SystemConfigHistory.class));
        verify(auditService, never()).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
}
