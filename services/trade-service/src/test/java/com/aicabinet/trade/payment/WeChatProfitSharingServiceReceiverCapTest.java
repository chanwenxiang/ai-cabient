package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.service.SystemConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分账「单接收方比例上限」闸（资金 #13 / MA-389）。
 *
 * <p>背景：本仓分账模型是「商户作为接收方拿走 {@code gross - platform}」，而渠道对单接收方有比例
 * 上限（CB-032 记的默认 30%，仅 ⚪ 旁证）。平台抽成配成现实档位（5%）时接收方占比 95%，
 * 请求必被渠道拒绝 ⇒ 批量分账失败。本闸在**唯一的提交出口**把它换成确定性本地拒绝。
 *
 * <p>🔴 用例必须同时钉住三件事，缺一条这道闸就只是「看起来有」：
 * ① 超限 ⇒ 不发请求（{@code verifyNoInteractions(client)}）+ 状态不动 + 原因落库；
 * ② 恰好等于上限 ⇒ 放行（边界是 {@code >} 不是 {@code >=}，否则 30% 这种合法档位被误杀）；
 * ③ 上限可运行期调整（拿到微信书面确认后要能改，不必发版）。
 */
@ExtendWith(MockitoExtension.class)
class WeChatProfitSharingServiceReceiverCapTest {
    static {
        // prepareSubmit 用 LambdaUpdateWrapper 落 out_order_no：纯 Mockito 环境需注册 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderRevenueSplit.class);
    }

    @Mock private WeChatPayV3Client client;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private SystemConfigService systemConfig;

    private WeChatProfitSharingService service;

    @BeforeEach
    void setUp() {
        ProfitSharingProperties sharing = new ProfitSharingProperties(true, false, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        service = new WeChatProfitSharingService(sharing, pay, client, splitRepository, null,
                new ProfitSharingCapPolicy(systemConfig), null);
    }

    private static Merchant merchant() {
        Merchant m = new Merchant();
        m.setMerchantId("M-1");
        m.setWechatReceiverId("1900000109");
        return m;
    }

    private static OrderRevenueSplit split(long platformCents, long merchantCents) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("SPLIT-C1");
        s.setOrderId("O-C1");
        s.setMerchantId("M-1");
        s.setGrossCents(platformCents + merchantCents);
        s.setPlatformCents(platformCents);
        s.setMerchantCents(merchantCents);
        s.setStatus("ACCRUED");
        return s;
    }

    /** ① 95% 接收方份额（平台抽 5% 的真实档位）⇒ 本地拒绝，绝不发请求。 */
    @Test
    void prepareSubmit_receiverShareAboveCap_isRefusedLocally() {
        when(systemConfig.getInt(eq(SystemConfigService.PROFIT_SHARING_RECEIVER_MAX_PERCENT), anyInt()))
                .thenReturn(30);
        OrderRevenueSplit s = split(50, 950);

        WeChatProfitSharingService.PreparedSubmission prep = service.prepareSubmit(s, merchant(), "WX-TXN-C1");

        assertTrue(prep.terminal());
        assertNull(prep.body());
        assertEquals("ACCRUED", s.getStatus(), "状态不得翻转：本方法还没打渠道，翻成 FAILED 是谎称已尝试");
        assertTrue(s.getFailureReason().contains("超过单接收方上限 30%"),
                "原因要能让运营直接照做，实际=" + s.getFailureReason());
        verify(splitRepository).save(s);
        // out_order_no 也不该落库（没提交就不该产生幂等键）
        verify(splitRepository, never()).update(any(), any());
        verifyNoInteractions(client);
    }

    /** ② 恰好 30% ⇒ 放行（边界是严格大于，30% 是合法档位不是违规）。 */
    @Test
    void prepareSubmit_receiverShareExactlyAtCap_isAllowed() {
        when(systemConfig.getInt(eq(SystemConfigService.PROFIT_SHARING_RECEIVER_MAX_PERCENT), anyInt()))
                .thenReturn(30);
        OrderRevenueSplit s = split(700, 300);

        WeChatProfitSharingService.PreparedSubmission prep = service.prepareSubmit(s, merchant(), "WX-TXN-C2");

        assertFalse(prep.terminal());
        assertNull(s.getFailureReason());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receivers = (List<Map<String, Object>>) prep.body().get("receivers");
        assertEquals(300L, receivers.get(0).get("amount"));
        verifyNoInteractions(client);
    }

    /** ③ 上限可运行期调整：配 95% 后同一条 90% 的分账单应放行。 */
    @Test
    void prepareSubmit_capIsRuntimeTunable() {
        when(systemConfig.getInt(eq(SystemConfigService.PROFIT_SHARING_RECEIVER_MAX_PERCENT), anyInt()))
                .thenReturn(95);
        OrderRevenueSplit s = split(100, 900);

        WeChatProfitSharingService.PreparedSubmission prep = service.prepareSubmit(s, merchant(), "WX-TXN-C3");

        assertFalse(prep.terminal());
        verifyNoInteractions(client);
    }

    /** ④ 配置被填 0 ⇒ 回落 30 而不是「不限制」——0 在这条闸上等于关闸门，必须 fail-closed。 */
    @Test
    void prepareSubmit_zeroConfiguredCap_fallsBackToDefaultNotUnlimited() {
        when(systemConfig.getInt(anyString(), anyInt())).thenReturn(0);
        OrderRevenueSplit s = split(50, 950);

        WeChatProfitSharingService.PreparedSubmission prep = service.prepareSubmit(s, merchant(), "WX-TXN-C4");

        assertTrue(prep.terminal(), "0 不得被读成「不限制」");
        assertTrue(s.getFailureReason().contains("上限 30%"));
        verifyNoInteractions(client);
    }

    /** ⑤ 越界配置（>100）同样回落默认。 */
    @Test
    void prepareSubmit_configuredCapAboveHundred_fallsBackToDefault() {
        when(systemConfig.getInt(anyString(), anyInt())).thenReturn(150);
        OrderRevenueSplit s = split(50, 950);

        assertTrue(service.prepareSubmit(s, merchant(), "WX-TXN-C5").terminal());
        assertTrue(s.getFailureReason().contains("上限 30%"));
    }
}
