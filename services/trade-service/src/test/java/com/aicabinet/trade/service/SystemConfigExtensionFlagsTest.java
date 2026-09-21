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
        assertEquals("false", consumer.get("adBannerEnabled"));
        assertEquals("false", service.merchantPublicConfig().get("chartsEnabled"));
    }

    @Test
    void extensionFlags_followStoredValue() {
        stub(SystemConfigService.CONSUMER_ORDER_SEARCH_ENABLED, "true");
        stub(SystemConfigService.CONSUMER_COUPON_ENTRY_ENABLED, "true");
        stub(SystemConfigService.CONSUMER_AD_BANNER_ENABLED, "true");
        stub(SystemConfigService.MERCHANT_CHARTS_ENABLED, "true");

        var consumer = service.consumerPublicConfig();
        assertEquals("true", consumer.get("orderSearchEnabled"));
        assertEquals("true", consumer.get("couponEntryEnabled"));
        assertEquals("true", consumer.get("adBannerEnabled"));
        assertEquals("true", service.merchantPublicConfig().get("chartsEnabled"));
    }

    @Test
    void extensionFlags_blankStoredValue_staysFalse() {
        stub(SystemConfigService.MERCHANT_CHARTS_ENABLED, "   ");
        stub(SystemConfigService.CONSUMER_AD_BANNER_ENABLED, "   ");
        assertEquals("false", service.merchantPublicConfig().get("chartsEnabled"));
        assertEquals("false", service.consumerPublicConfig().get("adBannerEnabled"));
    }

    // ── 腾讯流量主广告位（consumer.wx_ad.*，2026-09-20 第二十八轮）──────────────
    //
    // 我们是流量主（收腾讯分成），故「广告单元 ID」是**账号资产**、做成配置而非常量。
    // 这两条守住 fail-closed 的两道闸：开关默认关；ID 默认空且空白一律收敛为空串
    //（空 unit-id 喂给微信广告组件会触发组件自身报错）。

    @Test
    void wxAd_defaultsToDisabledAndEmptyUnitId_whenNoRowInDb() {
        var consumer = service.consumerPublicConfig();
        assertEquals("false", consumer.get("wxAdEnabled"));
        assertEquals("", consumer.get("wxAdUnitId"));
    }

    @Test
    void wxAd_followsStoredValues() {
        stub(SystemConfigService.CONSUMER_WX_AD_ENABLED, "true");
        stub(SystemConfigService.CONSUMER_WX_AD_UNIT_ID, "adunit-abc123");

        var consumer = service.consumerPublicConfig();
        assertEquals("true", consumer.get("wxAdEnabled"));
        assertEquals("adunit-abc123", consumer.get("wxAdUnitId"));
    }

    /**
     * 运营从后台复制 ID 时极易带上首尾空白/换行 ⇒ 下发给客户端前必须收敛，
     * 否则前端拿到 `"adunit-x "` 传给广告组件会被判成非法 unit-id。
     * 而“全空白”应回落成空串（= 不渲染），不能变成 `"   "` 这种“看起来非空”的值。
     */
    @Test
    void wxAdUnitId_isTrimmed_andBlankFallsBackToEmpty() {
        stub(SystemConfigService.CONSUMER_WX_AD_UNIT_ID, "  adunit-trimmed \n");
        assertEquals("adunit-trimmed", service.consumerPublicConfig().get("wxAdUnitId"));

        // 全空白 ⇒ 落到 getValue 的 defaultValue("") ，不能下发 "   " 这种“看着非空”的值
        stub(SystemConfigService.CONSUMER_WX_AD_UNIT_ID, "   ");
        assertEquals("", service.consumerPublicConfig().get("wxAdUnitId"));
    }

    /**
     * 🔴 两个键**互相独立**：开关开但没填 ID ⇒ 仍是「不可渲染」。
     * 前端 `wxAdAvailable = wxAdEnabled() && adUnitId !== ''` 正是靠这条服务端契约成立；
     * 若哪天把「开关开」当成「可以渲染」，这条会红。
     */
    @Test
    void wxAd_enabledWithoutUnitId_stillReportsBothSeparately() {
        stub(SystemConfigService.CONSUMER_WX_AD_ENABLED, "true");

        var consumer = service.consumerPublicConfig();
        assertEquals("true", consumer.get("wxAdEnabled"), "开关必须如实下发");
        assertEquals("", consumer.get("wxAdUnitId"), "没配 ID 就必须是空串，前端据此不渲染");
    }
}
