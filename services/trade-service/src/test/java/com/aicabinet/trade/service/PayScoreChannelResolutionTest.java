package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.domain.UserInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** B1-B0：自动定腿必须与 PayScoreService.charge 的选择顺序一致（纯函数）。 */
class PayScoreChannelResolutionTest {

    @Test
    void userPreferenceBalance_winsOverEntryChannel() {
        UserInfo user = new UserInfo(); // 默认 payPreferredChannel=BALANCE

        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(user, PayChannels.WECHAT));
    }

    @Test
    void explicitBalanceArgument_wins() {
        UserInfo user = new UserInfo();
        user.setPayPreferredChannel(null);

        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(user, PayChannels.BALANCE));
    }

    @Test
    void wechatContract_resolvesWechat() {
        UserInfo user = new UserInfo();
        user.setPayPreferredChannel(null);
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("PS-CONTRACT");

        assertEquals(PayChannels.WECHAT,
                PayScoreService.resolveChargeChannel(user, PayChannels.WECHAT));
    }

    @Test
    void alipayAgreement_resolvesAlipay() {
        UserInfo user = new UserInfo();
        user.setPayPreferredChannel(null);
        user.setAlipayAgreementId("ALI-AGREEMENT");

        assertEquals(PayChannels.ALIPAY,
                PayScoreService.resolveChargeChannel(user, PayChannels.ALIPAY));
    }

    @Test
    void entryWechatWithoutContract_fallsBackToAlipayAgreement() {
        UserInfo user = new UserInfo();
        user.setPayPreferredChannel(null);
        user.setAlipayAgreementId("ALI-AGREEMENT");

        assertEquals(PayChannels.ALIPAY,
                PayScoreService.resolveChargeChannel(user, PayChannels.WECHAT));
    }

    @Test
    void pendingAlipayAgreement_isNotActive() {
        UserInfo user = new UserInfo();
        user.setPayPreferredChannel(null);
        user.setAlipayAgreementId(PayScoreService.ALIPAY_PENDING_PREFIX + "X");

        assertEquals(PayChannels.BALANCE,
                PayScoreService.resolveChargeChannel(user, PayChannels.ALIPAY));
    }
}
