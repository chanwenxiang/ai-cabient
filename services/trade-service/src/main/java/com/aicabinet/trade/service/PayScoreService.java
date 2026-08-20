package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AgreementChargeClient;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PayScoreService {

    private static final Logger log = LoggerFactory.getLogger(PayScoreService.class);

    private final PayScoreProperties payScoreProperties;
    private final SecurityProperties securityProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final UserInfoMapper userInfoRepository;
    private final AlipayPayClient alipayPayClient;
    private final AgreementChargeClient agreementChargeClient;

    public PayScoreService(PayScoreProperties payScoreProperties,
                           SecurityProperties securityProperties,
                           WeChatPayProperties weChatPayProperties,
                           UserInfoMapper userInfoRepository,
                           AlipayPayClient alipayPayClient,
                           AgreementChargeClient agreementChargeClient) {
        this.payScoreProperties = payScoreProperties;
        this.securityProperties = securityProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.userInfoRepository = userInfoRepository;
        this.alipayPayClient = alipayPayClient;
        this.agreementChargeClient = agreementChargeClient;
    }

    public boolean isPasswordFreeReady(UserInfo user) {
        if (user == null) {
            return false;
        }
        if (user.isPayscoreEnabled() && user.getPayscoreContractId() != null && !user.getPayscoreContractId().isBlank()) {
            return true;
        }
        return user.getAlipayAgreementId() != null && !user.getAlipayAgreementId().isBlank();
    }

    /** 按扫码渠道判断免密是否就绪；channel 为空则任意渠道均可。 */
    public boolean isPasswordFreeReadyForChannel(UserInfo user, String channel) {
        if (user == null) {
            return false;
        }
        String entry = PayChannels.normalizeEntryChannel(channel);
        if (PayChannels.WECHAT.equals(entry)) {
            return user.isPayscoreEnabled()
                    && user.getPayscoreContractId() != null
                    && !user.getPayscoreContractId().isBlank();
        }
        if (PayChannels.ALIPAY.equals(entry)) {
            return user.getAlipayAgreementId() != null && !user.getAlipayAgreementId().isBlank();
        }
        return isPasswordFreeReady(user);
    }

    public String signWeChatPayScore(Long userId) {
        UserInfo user = requireUser(userId);
        if (!payScoreProperties.enabled() && !securityProperties.mockEnabled()) {
            throw new IllegalStateException("微信支付分未启用");
        }
        String contractId = "PSC-" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId(contractId);
        if (!PayChannels.BALANCE.equalsIgnoreCase(
                user.getPayPreferredChannel() == null ? "" : user.getPayPreferredChannel().trim())) {
            user.setPayPreferredChannel(PayChannels.WECHAT);
        }
        userInfoRepository.save(user);
        log.info("payscore contract signed user={} contract={}", userId, contractId);
        return contractId;
    }

    public String signAlipayAgreement(Long userId) {
        UserInfo user = requireUser(userId);
        if (!alipayPayClient.isConfigured() && !securityProperties.mockEnabled()) {
            throw new IllegalStateException("支付宝代扣未启用");
        }
        String agreementId = "ALI-AG-" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        user.setAlipayAgreementId(agreementId);
        if (!PayChannels.BALANCE.equalsIgnoreCase(
                user.getPayPreferredChannel() == null ? "" : user.getPayPreferredChannel().trim())) {
            user.setPayPreferredChannel(PayChannels.ALIPAY);
        }
        userInfoRepository.save(user);
        log.info("alipay agreement signed user={} agreement={}", userId, agreementId);
        return agreementId;
    }

    public ChargeResult charge(UserInfo user, String orderId, int amountCents, String description) {
        return charge(user, orderId, amountCents, description, null);
    }

    /**
     * @param preferredChannel 会话扫码渠道优先；为空则用用户偏好，再回落到已签约渠道，最后余额。
     *                         若用户偏好为 BALANCE，则始终优先扣余额（不被扫码入口 WECHAT/ALIPAY 覆盖）。
     */
    public ChargeResult charge(UserInfo user, String orderId, int amountCents, String description,
                               String preferredChannel) {
        if (amountCents <= 0) {
            return new ChargeResult(PayChannels.BALANCE, null);
        }
        String userPrefRaw = user.getPayPreferredChannel();
        if (userPrefRaw != null && PayChannels.BALANCE.equalsIgnoreCase(userPrefRaw.trim())) {
            return new ChargeResult(PayChannels.BALANCE, null);
        }
        if (preferredChannel != null && PayChannels.BALANCE.equalsIgnoreCase(preferredChannel.trim())) {
            return new ChargeResult(PayChannels.BALANCE, null);
        }
        String preferred = PayChannels.normalizeEntryChannel(preferredChannel);
        if (preferred == null) {
            preferred = PayChannels.normalizeEntryChannel(user.getPayPreferredChannel());
        }

        if (PayChannels.WECHAT.equals(preferred)
                && user.isPayscoreEnabled()
                && user.getPayscoreContractId() != null
                && !user.getPayscoreContractId().isBlank()) {
            return chargeWeChatPayScore(user, orderId, amountCents, description);
        }
        if (PayChannels.ALIPAY.equals(preferred)
                && user.getAlipayAgreementId() != null
                && !user.getAlipayAgreementId().isBlank()) {
            return chargeAlipayAgreement(user, orderId, amountCents, description);
        }
        if (user.isPayscoreEnabled() && user.getPayscoreContractId() != null
                && !user.getPayscoreContractId().isBlank()) {
            return chargeWeChatPayScore(user, orderId, amountCents, description);
        }
        if (user.getAlipayAgreementId() != null && !user.getAlipayAgreementId().isBlank()) {
            return chargeAlipayAgreement(user, orderId, amountCents, description);
        }
        return new ChargeResult(PayChannels.BALANCE, null);
    }

    private ChargeResult chargeWeChatPayScore(UserInfo user, String orderId, int amountCents, String description) {
        if (weChatPayProperties.isConfigured() && payScoreProperties.enabled() && payScoreProperties.liveChargeEnabled()) {
            String tradeNo = agreementChargeClient.charge(new AgreementChargeClient.ChargeRequest(
                    PayChannels.WECHAT,
                    user.getUserId(),
                    orderId,
                    user.getPayscoreContractId(),
                    amountCents,
                    description
            )).tradeNo();
            log.info("payscore live charge user={} order={} amount={}", user.getUserId(), orderId, amountCents);
            return new ChargeResult(PayChannels.WECHAT, tradeNo);
        }
        // PAYSCORE_MOCK_ENABLED gates mock charge together with global security mock
        if (securityProperties.mockEnabled() && payScoreProperties.mockEnabled()) {
            String tradeNo = "MOCK-PS-" + orderId;
            log.info("payscore mock charge user={} order={} amount={} desc={}",
                    user.getUserId(), orderId, amountCents, description);
            return new ChargeResult(PayChannels.WECHAT, tradeNo);
        }
        throw new IllegalStateException(
                "WeChat PayScore charge unavailable: enable PAYSCORE_LIVE_CHARGE_ENABLED with a charge gateway, "
                        + "or enable mock (AICABINET_MOCK_ENABLED + PAYSCORE_MOCK_ENABLED). Silent balance fallback is disabled.");
    }

    private ChargeResult chargeAlipayAgreement(UserInfo user, String orderId, int amountCents, String description) {
        if (alipayPayClient.isConfigured() && payScoreProperties.liveChargeEnabled()) {
            String tradeNo = agreementChargeClient.charge(new AgreementChargeClient.ChargeRequest(
                    PayChannels.ALIPAY,
                    user.getUserId(),
                    orderId,
                    user.getAlipayAgreementId(),
                    amountCents,
                    description
            )).tradeNo();
            log.info("alipay agreement live charge user={} order={} amount={}", user.getUserId(), orderId, amountCents);
            return new ChargeResult(PayChannels.ALIPAY, tradeNo);
        }
        if (securityProperties.mockEnabled() && payScoreProperties.mockEnabled()) {
            String tradeNo = "MOCK-ALI-" + orderId;
            log.info("alipay agreement mock charge user={} order={} amount={} desc={}",
                    user.getUserId(), orderId, amountCents, description);
            return new ChargeResult(PayChannels.ALIPAY, tradeNo);
        }
        throw new IllegalStateException(
                "Alipay agreement charge unavailable: enable PAYSCORE_LIVE_CHARGE_ENABLED with a charge gateway, "
                        + "or enable mock (AICABINET_MOCK_ENABLED + PAYSCORE_MOCK_ENABLED). Silent balance fallback is disabled.");
    }

    private UserInfo requireUser(Long userId) {
        return userInfoRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public record ChargeResult(String channel, String tradeNo) {}
}
