package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.domain.UserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B1-B0：自动定腿必须与 {@link PayScoreService#charge} 的**严格入口规则**逐行一致（纯函数）。
 *
 * <p>2026-11-17 用户拍板：用户持久偏好已移除；入口/显式渠道决定扣款方式，且**不跨渠道回落**——
 * 微信扫码只会走微信支付分或余额，绝不会被静默扣支付宝；反之亦然。</p>
 */
class PayScoreChannelResolutionTest {

    private static UserInfo wechatReady() {
        UserInfo user = new UserInfo();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("PS-CONTRACT");
        return user;
    }

    private static UserInfo alipayReady() {
        UserInfo user = new UserInfo();
        user.setAlipayAgreementId("ALI-AGREEMENT");
        return user;
    }

    @Test
    @DisplayName("显式/入口 BALANCE ⇒ 余额（即使已签微信支付分）")
    void explicitBalance_wins() {
        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(wechatReady(), PayChannels.BALANCE));
    }

    @Test
    @DisplayName("微信入口 + 支付分合约 ⇒ 微信")
    void wechatEntryWithContract_resolvesWechat() {
        assertEquals(PayChannels.WECHAT,
                PayScoreService.resolveChargeChannel(wechatReady(), PayChannels.WECHAT));
    }

    @Test
    @DisplayName("支付宝入口 + 协议 ⇒ 支付宝")
    void alipayEntryWithAgreement_resolvesAlipay() {
        assertEquals(PayChannels.ALIPAY,
                PayScoreService.resolveChargeChannel(alipayReady(), PayChannels.ALIPAY));
    }

    @Test
    @DisplayName("🔴 微信入口但只有支付宝协议 ⇒ 余额（不跨渠道扣支付宝）")
    void wechatEntryWithoutContract_doesNotFallBackToAlipay() {
        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(alipayReady(), PayChannels.WECHAT));
    }

    @Test
    @DisplayName("🔴 支付宝入口但只有微信支付分 ⇒ 余额（不跨渠道扣微信）")
    void alipayEntryWithoutAgreement_doesNotFallBackToWechat() {
        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(wechatReady(), PayChannels.ALIPAY));
    }

    @Test
    @DisplayName("无入口通道 ⇒ 余额（不再读用户偏好）")
    void noEntry_resolvesBalance() {
        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(wechatReady(), null));
        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(alipayReady(), ""));
    }

    @Test
    @DisplayName("PENDING 支付宝协议不算就绪 ⇒ 余额")
    void pendingAlipayAgreement_isNotActive() {
        UserInfo user = new UserInfo();
        user.setAlipayAgreementId(PayScoreService.ALIPAY_PENDING_PREFIX + "X");

        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(user, PayChannels.ALIPAY));
    }
}
