package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MerchantDailyReportProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.wechat.MiniAppTarget;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import com.aicabinet.trade.wechat.WeChatOfficialAccountClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商户每日经营日报（MA-377）。
 *
 * <p>钉住的四件事：① 未配模板/渠道关/商户没人绑微信时**不发且不抛**（fail-closed）；
 * ② 目标端必须是 MERCHANT（用消费者 token 发商户 openid 必回 400，静默废掉整个功能）；
 * ③ data 键名全部来自配置（模板换关键词不用改代码）；④ 同一人同一天只发一条（重跑不轰炸）。
 */
class MerchantDailyReportNotifierTest {

    private static final String MERCHANT_ID = "M1";
    private static final String BIZ_ID = "2026-10-15";

    private MerchantDailyReportNotifier.MerchantDailyReport report() {
        return new MerchantDailyReportNotifier.MerchantDailyReport(
                MERCHANT_ID, BIZ_ID, "2026年10月15日", 190_123L, 293);
    }

    private WeChatMiniAppProperties merchantReady() {
        return new WeChatMiniAppProperties(true, "legacy-id", "legacy-secret",
                "tpl-merchant", "pages/alerts/alerts",
                null, null,
                "consumer-app", "consumer-secret", "merchant-app", "merchant-secret", "formal");
    }

    private MerchantDailyReportProperties configured() {
        return new MerchantDailyReportProperties(true, "tpl-daily-report", "", "", "", "", "", "", "");
    }

    private WeChatOfficialAccountProperties officialDisabled() {
        return new WeChatOfficialAccountProperties(false, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
    }

    private WeChatOfficialAccountProperties officialReady() {
        return new WeChatOfficialAccountProperties(true, "gh_official", "secret", "consumer-tpl",
                "", "https://example.test/callback", "consumer-app", "pages/messages/messages",
                "merchant-app", "pages/home/home", "official-report-tpl", "official-refund-tpl", "",
                "", "official-dispute-tpl", "测试品牌");
    }

    private UserInfo operator(long userId, String openId) {
        UserInfo u = new UserInfo();
        u.setUserId(userId);
        u.setWxMerchantOpenId(openId);
        return u;
    }

    private OpsUserMerchant bind(long userId) {
        return new OpsUserMerchant(userId, MERCHANT_ID);
    }

    private record Fixture(MerchantDailyReportNotifier notifier,
                           WeChatMiniAppClient client,
                           WeChatOfficialAccountClient officialClient,
                           UserInfoMapper userRepo,
                           NotificationLogMapper logRepo) {
    }

    private Fixture fixture(MerchantDailyReportProperties props, boolean wechatOn, long logCount) {
        return fixture(props, wechatOn, logCount, officialDisabled());
    }

    private Fixture fixture(MerchantDailyReportProperties props, boolean wechatOn, long logCount,
                            WeChatOfficialAccountProperties officialProps) {
        OpsUserMerchantMapper bindRepo = mock(OpsUserMerchantMapper.class);
        when(bindRepo.findActiveByMerchantId(MERCHANT_ID)).thenReturn(List.of(bind(101L), bind(102L)));
        UserInfoMapper userRepo = mock(UserInfoMapper.class);
        when(userRepo.findById(101L)).thenReturn(Optional.of(operator(101L, "openid-101")));
        when(userRepo.findById(102L)).thenReturn(Optional.of(operator(102L, "")));
        MerchantMapper merchantRepo = mock(MerchantMapper.class);
        Merchant merchant = new Merchant();
        merchant.setMerchantId(MERCHANT_ID);
        merchant.setMerchantName("测试商户");
        when(merchantRepo.selectById(MERCHANT_ID)).thenReturn(merchant);
        WeChatMiniAppClient client = mock(WeChatMiniAppClient.class);
        when(client.sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        WeChatOfficialAccountClient officialClient = mock(WeChatOfficialAccountClient.class);
        NotifyChannelSwitchService channelSwitch = mock(NotifyChannelSwitchService.class);
        when(channelSwitch.wechatEnabled()).thenReturn(wechatOn);
        NotificationLogMapper logRepo = mock(NotificationLogMapper.class);
        when(logRepo.selectCount(any())).thenReturn(logCount);
        MerchantDailyReportNotifier notifier = new MerchantDailyReportNotifier(
                bindRepo, userRepo, merchantRepo, client, merchantReady(), officialClient, officialProps,
                props, channelSwitch, logRepo);
        return new Fixture(notifier, client, officialClient, userRepo, logRepo);
    }

    @Test
    void merchantFallback_doesNotUseConsumerOpenId() {
        Fixture f = fixture(configured(), true, 0L);
        UserInfo consumerOnly = operator(101L, null);
        consumerOnly.setWxOpenId("consumer-openid");
        when(f.userRepo.findById(101L)).thenReturn(Optional.of(consumerOnly));

        MerchantDailyReportNotifier.DeliveryResult result = f.notifier.send(new MerchantDailyReportNotifier.MerchantDailyReport(
                MERCHANT_ID, "2026-11-26", "2026-11-26", 0, 0));

        assertEquals(0, result.sent());
        verify(f.client, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
        verify(f.logRepo, never()).insert(any(NotificationLog.class));
    }

    @Test
    void sendsToMerchantMiniappWithConfiguredKeys() {
        Fixture f = fixture(configured(), true, 0L);

        MerchantDailyReportNotifier.DeliveryResult result = f.notifier().send(report());

        assertEquals(1, result.sent(), "101 有 openid、102 没有 ⇒ 只发 1 人");
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(f.client()).sendSubscribeMessage(eq("openid-101"), eq("tpl-daily-report"),
                eq("pages/home/home"), data.capture(), eq(MiniAppTarget.MERCHANT));
        assertEquals("1901.23", data.getValue().get("amount1"), "销售额（元，两位小数）");
        assertEquals("293", data.getValue().get("number2"), "成交笔数");
        assertEquals("6.49", data.getValue().get("amount3"), "客单价 1901.23/293 四舍五入");
        assertEquals(3, data.getValue().size(), "只发模板已定义的三项，多传键会被微信拒");
    }

    @Test
    void keywordNamesFollowConfig_notHardcoded() {
        // 换模板（关键词叫 amount5/sales_date…）只应改 env，不该改代码
        Fixture f = fixture(new MerchantDailyReportProperties(true, "tpl-2", "pages/home/home",
                "salesAmount", "orderCount", "unitPrice", "", "", ""), true, 0L);

        f.notifier().send(report());

        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(f.client()).sendSubscribeMessage(anyString(), eq("tpl-2"), anyString(), data.capture(),
                eq(MiniAppTarget.MERCHANT));
        assertTrue(data.getValue().containsKey("salesAmount"));
        assertTrue(data.getValue().containsKey("orderCount"));
        assertTrue(data.getValue().containsKey("unitPrice"));
    }

    @Test
    void boundOfficialOperator_usesSettlementTemplateFields() {
        Fixture f = fixture(configured(), true, 0L, officialReady());
        UserInfo u = operator(101L, "mini-openid");
        u.setWxOfficialOpenId("official-openid");
        u.setWxOfficialAppId("gh_official");
        when(f.userRepo().findById(101L)).thenReturn(Optional.of(u));
        when(f.officialClient().sendTemplateMessage(anyString(), anyMap(), anyString(),
                eq(MiniAppTarget.MERCHANT), anyString())).thenReturn(true);

        MerchantDailyReportNotifier.DeliveryResult result = f.notifier().send(report());

        assertEquals(1, result.sent());
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(f.officialClient()).sendTemplateMessage(eq("official-openid"), data.capture(), anyString(),
                eq(MiniAppTarget.MERCHANT), eq("pages/home/home"));
        assertEquals(Map.of("amount9", "1901.23", "thing16", "测试商户", "time11", "2026年10月15日"),
                data.getValue(), "服务号模板键名与金额/商户名/时间映射");
        verify(f.client(), never()).sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void skipsEverythingWhenTemplateNotConfigured() {
        // 未配模板：不是「发一条空消息」，而是根本不打微信接口（fail-closed）
        Fixture f = fixture(MerchantDailyReportProperties.disabled(), true, 0L);

        assertEquals(0, f.notifier().send(report()).sent());
        verify(f.client(), never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void skipsWhenWechatChannelSwitchedOff() {
        Fixture f = fixture(configured(), false, 0L);

        assertEquals(0, f.notifier().send(report()).sent());
        verify(f.client(), never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void sameOperatorSameDayNotSentTwice() {
        // 调度重跑 / 运营台按日期重跑：站内信按商户幂等，微信按「人 × 日期」幂等
        Fixture f = fixture(configured(), true, 1L);

        assertEquals(0, f.notifier().send(report()).sent());
        verify(f.client(), never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void failedWechatSendIsRecordedAsFailedAndCanBeRetried() {
        Fixture f = fixture(configured(), true, 0L);
        when(f.client().sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false);

        MerchantDailyReportNotifier.DeliveryResult result = f.notifier().send(report());

        assertEquals(0, result.sent());
        assertEquals(1, result.failed());
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(f.logRepo()).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
    }

    @Test
    void businessSwitchDisabled_blocksOfficialChannelToo() {
        Fixture f = fixture(MerchantDailyReportProperties.disabled(), true, 0L, officialReady());

        assertEquals(MerchantDailyReportNotifier.DeliveryResult.EMPTY, f.notifier().send(report()));

        verify(f.officialClient(), never()).sendTemplateMessage(anyString(), anyMap(), anyString(),
                any(MiniAppTarget.class), anyString());
        verify(f.client, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
    }

    private Fixture officialFixture(boolean sendOk) {
        OpsUserMerchantMapper bindRepo = mock(OpsUserMerchantMapper.class);
        when(bindRepo.findActiveByMerchantId(MERCHANT_ID)).thenReturn(List.of(bind(101L)));
        UserInfo user = operator(101L, "mini-openid");
        user.setWxOfficialOpenId("official-openid");
        user.setWxOfficialAppId("gh_official");
        UserInfoMapper userRepo = mock(UserInfoMapper.class);
        when(userRepo.findById(101L)).thenReturn(Optional.of(user));
        WeChatMiniAppClient client = mock(WeChatMiniAppClient.class);
        WeChatOfficialAccountClient officialClient = mock(WeChatOfficialAccountClient.class);
        when(officialClient.sendTemplateMessage(anyString(), anyMap(), anyString(),
                eq(MiniAppTarget.MERCHANT), eq("pages/home/home"))).thenReturn(sendOk);
        NotifyChannelSwitchService channelSwitch = mock(NotifyChannelSwitchService.class);
        when(channelSwitch.wechatEnabled()).thenReturn(true);
        NotificationLogMapper logRepo = mock(NotificationLogMapper.class);
        when(logRepo.selectCount(any())).thenReturn(0L);
        MerchantMapper merchantRepo = mock(MerchantMapper.class);
        Merchant merchant = new Merchant();
        merchant.setMerchantId(MERCHANT_ID);
        merchant.setMerchantName("测试商户");
        when(merchantRepo.selectById(MERCHANT_ID)).thenReturn(merchant);
        MerchantDailyReportNotifier notifier = new MerchantDailyReportNotifier(
                bindRepo, userRepo, merchantRepo, client, merchantReady(), officialClient, officialReady(),
                configured(), channelSwitch, logRepo);
        return new Fixture(notifier, client, officialClient, userRepo, logRepo);
    }

    @Test
    void officialBoundOperatorUsesServiceAccountAndMerchantJump() {
        Fixture f = officialFixture(true);

        assertEquals(1, f.notifier().send(report()).sent());

        verify(f.officialClient()).sendTemplateMessage(eq("official-openid"), anyMap(),
                eq("merchant-daily-report:" + MERCHANT_ID + ":101:" + BIZ_ID), eq(MiniAppTarget.MERCHANT),
                eq("pages/home/home"));
        verify(f.client(), never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(f.logRepo()).insert(log.capture());
        assertEquals("WECHAT_OFFICIAL", log.getValue().getChannel());
        assertEquals("merchant-daily-report:" + MERCHANT_ID + ":101:" + BIZ_ID, log.getValue().getClientMsgId());
    }

    @Test
    void officialFailureDoesNotFallbackAndIsVisible() {
        Fixture f = officialFixture(false);

        MerchantDailyReportNotifier.DeliveryResult result = f.notifier().send(report());

        assertEquals(0, result.sent());
        assertEquals(1, result.failed());
        verify(f.client(), never()).sendSubscribeMessage(anyString(), anyString(), anyString(), any(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(f.logRepo()).insert(log.capture());
        assertEquals("FAILED", log.getValue().getStatus());
        assertEquals("WECHAT_OFFICIAL", log.getValue().getChannel());
    }

    @Test
    void avgCentsRoundsAndHandlesZeroOrders() {
        assertEquals(649L, new MerchantDailyReportNotifier.MerchantDailyReport(
                MERCHANT_ID, BIZ_ID, "p", 190_123L, 293).avgCents());
        assertEquals(0L, new MerchantDailyReportNotifier.MerchantDailyReport(
                MERCHANT_ID, BIZ_ID, "p", 0L, 0).avgCents(), "0 笔不得除零");
    }
}
