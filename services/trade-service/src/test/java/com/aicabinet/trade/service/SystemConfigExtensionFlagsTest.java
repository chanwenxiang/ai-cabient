package com.aicabinet.trade.service;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.domain.SystemConfig;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 「扩展功能域」开关的下发契约（2026-09-20 第二十七轮）。
 *
 * <p>守住两件事：</p>
 * <ol>
 *   <li><b>默认全关</b>：库里没有该行时按 {@code false} 下发 ⇒ 运营台未启用时，
 *       客户端行为与接入前**完全一致**（这也是 fail-closed 的证明）；</li>
 *   <li><b>值真的跟随</b>：库里写了 {@code true}，下发值必须跟着变 ——
 *       否则就是「开关能点、界面不动」的**假可配置**。</li>
 * </ol>
 *
 * <p>⚠️ 本类只验证**下发值**；「客户端拿到后真的显隐」由各自的页面逻辑与前端构建保证。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemConfigExtensionFlagsTest {

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
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        // 基线：库里什么都没有 ⇒ 全部走默认值
        lenient()
                .when(repository.findById(ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    /** 让某个键在「库」里有值。 */
    private void stub(String key, String value) {
        SystemConfig row = new SystemConfig();
        row.setConfigKey(key);
        row.setConfigValue(value);
        when(repository.findById(key)).thenReturn(Optional.of(row));
    }

    @Test
    void extensionFlags_defaultToFalse_whenNoRowInDb() {
        var consumer = service.consumerPublicConfig();
        assertEquals("false", consumer.get("orderSearchEnabled"));
        assertEquals("false", consumer.get("couponEntryEnabled"));
        assertEquals("false", service.merchantPublicConfig().get("chartsEnabled"));
    }

    @Test
    void extensionFlags_followStoredValue() {
        stub(SystemConfigService.CONSUMER_ORDER_SEARCH_ENABLED, "true");
        stub(SystemConfigService.CONSUMER_COUPON_ENTRY_ENABLED, "true");
        stub(SystemConfigService.MERCHANT_CHARTS_ENABLED, "true");

        var consumer = service.consumerPublicConfig();
        assertEquals("true", consumer.get("orderSearchEnabled"));
        assertEquals("true", consumer.get("couponEntryEnabled"));
        assertEquals("true", service.merchantPublicConfig().get("chartsEnabled"));
    }

    @Test
    void extensionFlags_blankStoredValue_staysFalse() {
        stub(SystemConfigService.MERCHANT_CHARTS_ENABLED, "   ");
        assertEquals("false", service.merchantPublicConfig().get("chartsEnabled"));
    }
}
