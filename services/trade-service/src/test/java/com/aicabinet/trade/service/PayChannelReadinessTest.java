package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayScoreService#isChannelUsable} 是**两个调用方共用的唯一就绪判据**：
 * <ul>
 *   <li>{@link PayScoreService#chargeExplicit}（F6 结算页显式选渠道）；</li>
 *   <li>{@link AccountService}（个人中心设为「优先支付方式」，见 {@code AccountService#doSetPayPreferredChannel}）。</li>
 * </ul>
 *
 * <p>两处曾经**不一致**：设为优先渠道只判了协议号非空，于是「签约中」（{@code PENDING:} 前缀）的支付宝协议
 * 能被写成偏好渠道，而前端 {@code alipayAgreementEnabled} 用的是严格判据、会把该选项置灰 ——
 * 用户看到选项不可选、偏好里却存着它。故此处用真值表把语义钉死在「一处定义、两处同源」。
 */
class PayChannelReadinessTest {

    // ---------- 空值 ----------

    @Test
    void nullUserOrNullChannel_isNeverUsable() {
        assertFalse(PayScoreService.isChannelUsable(null, "BALANCE"));
        assertFalse(PayScoreService.isChannelUsable(user(), null));
        assertFalse(PayScoreService.isChannelUsable(null, null));
    }

    // ---------- 余额：永远是可用兜底 ----------

    @Test
    void balanceIsAlwaysUsable_regardlessOfSigningState() {
        assertTrue(PayScoreService.isChannelUsable(user(), "BALANCE"));
        UserInfo signed = user();
        signed.setPayscoreEnabled(true);
        signed.setPayscoreContractId("C-1");
        signed.setAlipayAgreementId("A-1");
        assertTrue(PayScoreService.isChannelUsable(signed, "BALANCE"));
    }

    @Test
    void channelValueIsTrimmedAndCaseInsensitive() {
        UserInfo u = user();
        u.setPayscoreEnabled(true);
        u.setPayscoreContractId("C-1");
        assertTrue(PayScoreService.isChannelUsable(u, "wechat"));
        assertTrue(PayScoreService.isChannelUsable(u, "  WeChat  "));
        assertTrue(PayScoreService.isChannelUsable(u, "balance"));
    }

    // ---------- 微信免密：需「已开通」且「协议号非空」 ----------

    @Test
    void wechatRequiresEnabledFlagAndContractId() {
        assertFalse(PayScoreService.isChannelUsable(user(), "WECHAT"), "两者皆缺");

        UserInfo enabledOnly = user();
        enabledOnly.setPayscoreEnabled(true);
        assertFalse(PayScoreService.isChannelUsable(enabledOnly, "WECHAT"), "缺协议号");

        UserInfo contractOnly = user();
        contractOnly.setPayscoreContractId("C-1");
        assertFalse(PayScoreService.isChannelUsable(contractOnly, "WECHAT"), "缺开通标记");

        UserInfo blankContract = user();
        blankContract.setPayscoreEnabled(true);
        blankContract.setPayscoreContractId("   ");
        assertFalse(PayScoreService.isChannelUsable(blankContract, "WECHAT"), "空白协议号不算签约");

        UserInfo ready = user();
        ready.setPayscoreEnabled(true);
        ready.setPayscoreContractId("C-1");
        assertTrue(PayScoreService.isChannelUsable(ready, "WECHAT"));
    }

    // ---------- 支付宝免密：PENDING: 前缀＝签约中，不可用 ----------

    @Test
    void alipayPendingAgreementIsNotUsable() {
        UserInfo u = user();
        u.setAlipayAgreementId(PayScoreService.ALIPAY_PENDING_PREFIX + "tmp-1");
        assertFalse(PayScoreService.isChannelUsable(u, "ALIPAY"));
    }

    @Test
    void alipayNeedsActiveAgreementId() {
        assertFalse(PayScoreService.isChannelUsable(user(), "ALIPAY"), "无协议号");

        UserInfo blank = user();
        blank.setAlipayAgreementId("   ");
        assertFalse(PayScoreService.isChannelUsable(blank, "ALIPAY"), "空白协议号");

        UserInfo ready = user();
        ready.setAlipayAgreementId("2026agreement-1");
        assertTrue(PayScoreService.isChannelUsable(ready, "ALIPAY"));
    }

    // ---------- 不得串台：一种渠道的就绪不得由另一种渠道的状态满足 ----------

    @Test
    void readinessDoesNotLeakAcrossChannels() {
        UserInfo wechatOnly = user();
        wechatOnly.setPayscoreEnabled(true);
        wechatOnly.setPayscoreContractId("C-1");
        assertTrue(PayScoreService.isChannelUsable(wechatOnly, "WECHAT"));
        assertFalse(PayScoreService.isChannelUsable(wechatOnly, "ALIPAY"), "微信签约不能顶替支付宝签约");

        UserInfo alipayOnly = user();
        alipayOnly.setAlipayAgreementId("A-1");
        assertTrue(PayScoreService.isChannelUsable(alipayOnly, "ALIPAY"));
        assertFalse(PayScoreService.isChannelUsable(alipayOnly, "WECHAT"), "支付宝签约不能顶替微信签约");
    }

    // ---------- 未知渠道一律不可用（调用方的入参校验兜底） ----------

    @Test
    void unknownChannelIsNotUsable() {
        UserInfo u = user();
        u.setPayscoreEnabled(true);
        u.setPayscoreContractId("C-1");
        u.setAlipayAgreementId("A-1");
        assertFalse(PayScoreService.isChannelUsable(u, "PAYPAL"));
        assertFalse(PayScoreService.isChannelUsable(u, ""));
    }

    private UserInfo user() {
        UserInfo user = new UserInfo();
        user.setUserId(10001L);
        return user;
    }
}
