package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AgreementChargeClient;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
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
    private final WeChatPayClient weChatPayClient;
    private final AlipayPayClient alipayPayClient;
    private final AgreementChargeClient agreementChargeClient;

    public PayScoreService(PayScoreProperties payScoreProperties,
                           SecurityProperties securityProperties,
                           WeChatPayProperties weChatPayProperties,
                           UserInfoMapper userInfoRepository,
                           WeChatPayClient weChatPayClient,
                           AlipayPayClient alipayPayClient,
                           AgreementChargeClient agreementChargeClient) {
        this.payScoreProperties = payScoreProperties;
        this.securityProperties = securityProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.userInfoRepository = userInfoRepository;
        this.weChatPayClient = weChatPayClient;
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

    public String signWeChatPayScore(Long userId) {
        UserInfo user = requireUser(userId);
        if (!payScoreProperties.enabled() && !securityProperties.mockEnabled()) {
            throw new IllegalStateException("微信支付分未启用");
        }
        String contractId = "PSC-" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId(contractId);
        user.setPayPreferredChannel(PayChannels.WECHAT);
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
        user.setPayPreferredChannel(PayChannels.ALIPAY);
        userInfoRepository.save(user);
        log.info("alipay agreement signed user={} agreement={}", userId, agreementId);
        return agreementId;
    }

    public ChargeResult charge(UserInfo user, String orderId, int amountCents, String description) {
        if (amountCents <= 0) {
            return new ChargeResult(PayChannels.BALANCE, null);
        }
        String preferred = user.getPayPreferredChannel() != null ? user.getPayPreferredChannel() : PayChannels.BALANCE;

        if (PayChannels.WECHAT.equalsIgnoreCase(preferred)
                && user.isPayscoreEnabled()
                && user.getPayscoreContractId() != null) {
            return chargeWeChatPayScore(user, orderId, amountCents, description);
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(preferred)
                && user.getAlipayAgreementId() != null) {
            return chargeAlipayAgreement(user, orderId, amountCents, description);
        }
        if (user.isPayscoreEnabled() && user.getPayscoreContractId() != null) {
            return chargeWeChatPayScore(user, orderId, amountCents, description);
        }
        if (user.getAlipayAgreementId() != null) {
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
        if (securityProperties.mockEnabled()) {
            String tradeNo = "MOCK-PS-" + orderId;
            log.info("payscore mock charge user={} order={} amount={} desc={}",
                    user.getUserId(), orderId, amountCents, description);
            return new ChargeResult(PayChannels.WECHAT, tradeNo);
        }
        if (weChatPayProperties.isConfigured() && payScoreProperties.enabled()) {
            throw new IllegalStateException(
                    "WeChat PayScore live charging is not enabled. Configure a real charge implementation before production use.");
        }
        return new ChargeResult(PayChannels.BALANCE, null);
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
        if (securityProperties.mockEnabled()) {
            String tradeNo = "MOCK-ALI-" + orderId;
            log.info("alipay agreement mock charge user={} order={} amount={} desc={}",
                    user.getUserId(), orderId, amountCents, description);
            return new ChargeResult(PayChannels.ALIPAY, tradeNo);
        }
        if (alipayPayClient.isConfigured()) {
            throw new IllegalStateException(
                    "Alipay agreement live charging is not enabled. Configure a real charge implementation before production use.");
        }
        return new ChargeResult(PayChannels.BALANCE, null);
    }

    private UserInfo requireUser(Long userId) {
        return userInfoRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public record ChargeResult(String channel, String tradeNo) {}
}
