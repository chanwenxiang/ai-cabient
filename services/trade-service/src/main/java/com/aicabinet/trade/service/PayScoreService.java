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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class PayScoreService {

    private static final Logger log = LoggerFactory.getLogger(PayScoreService.class);
    public static final String ALIPAY_PENDING_PREFIX = "PENDING:";

    private final PayScoreProperties payScoreProperties;
    private final SecurityProperties securityProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final UserInfoMapper userInfoRepository;
    private final AlipayPayClient alipayPayClient;
    private final AgreementChargeClient agreementChargeClient;
    private final DistributedLockService distributedLockService;

    public PayScoreService(PayScoreProperties payScoreProperties,
                           SecurityProperties securityProperties,
                           WeChatPayProperties weChatPayProperties,
                           UserInfoMapper userInfoRepository,
                           AlipayPayClient alipayPayClient,
                           AgreementChargeClient agreementChargeClient,
                           DistributedLockService distributedLockService) {
        this.payScoreProperties = payScoreProperties;
        this.securityProperties = securityProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.userInfoRepository = userInfoRepository;
        this.alipayPayClient = alipayPayClient;
        this.agreementChargeClient = agreementChargeClient;
        this.distributedLockService = distributedLockService;
    }

    public static boolean isActiveAlipayAgreementId(String agreementId) {
        return agreementId != null && !agreementId.isBlank() && !agreementId.startsWith(ALIPAY_PENDING_PREFIX);
    }

    public static String pendingExternalNo(String agreementId) {
        if (agreementId == null || !agreementId.startsWith(ALIPAY_PENDING_PREFIX)) {
            return null;
        }
        return agreementId.substring(ALIPAY_PENDING_PREFIX.length());
    }

    public boolean isPasswordFreeReady(UserInfo user) {
        if (user == null) {
            return false;
        }
        if (user.isPayscoreEnabled() && user.getPayscoreContractId() != null && !user.getPayscoreContractId().isBlank()) {
            return true;
        }
        return isActiveAlipayAgreementId(user.getAlipayAgreementId());
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
            return isActiveAlipayAgreementId(user.getAlipayAgreementId());
        }
        return isPasswordFreeReady(user);
    }

    @Transactional
    public String signWeChatPayScore(Long userId) {
        return runWithPayScoreUserLock(userId, () -> {
            UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalArgumentException("user not found"));
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
        });
    }

    /**
     * 开通支付宝免密。
     * <ul>
     *   <li>mock：即时写入真实协议号并 active</li>
     *   <li>已配置 OpenAPI：写入 PENDING:externalNo，返回签约表单，待异步通知激活</li>
     * </ul>
     */
    @Transactional
    public AlipaySignResult signAlipayAgreement(Long userId) {
        return runWithPayScoreUserLock(userId, () -> doSignAlipayAgreement(userId));
    }

    private AlipaySignResult doSignAlipayAgreement(Long userId) {
        UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (isActiveAlipayAgreementId(user.getAlipayAgreementId())) {
            return new AlipaySignResult(true, user.getAlipayAgreementId(), null, false);
        }
        if (!alipayPayClient.isConfigured() && !securityProperties.mockEnabled()) {
            throw new IllegalStateException("支付宝代扣未启用");
        }
        if (alipayPayClient.isConfigured() && !securityProperties.mockEnabled()) {
            String externalNo = "EXT-" + userId + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            user.setAlipayAgreementId(ALIPAY_PENDING_PREFIX + externalNo);
            userInfoRepository.save(user);
            String form = alipayPayClient.createAgreementSignForm(externalNo);
            log.info("alipay agreement pending user={} external={}", userId, externalNo);
            return new AlipaySignResult(false, externalNo, form, true);
        }
        // mock：即时开通
        String agreementId = "ALI-AG-" + userId + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        user.setAlipayAgreementId(agreementId);
        if (!PayChannels.BALANCE.equalsIgnoreCase(
                user.getPayPreferredChannel() == null ? "" : user.getPayPreferredChannel().trim())) {
            user.setPayPreferredChannel(PayChannels.ALIPAY);
        }
        userInfoRepository.save(user);
        log.info("alipay agreement signed (mock) user={} agreement={}", userId, agreementId);
        return new AlipaySignResult(true, agreementId, null, false);
    }

    /** 支付宝协议签约异步通知：用 external_agreement_no 绑定真实 agreement_no。 */
    @Transactional
    public boolean bindAlipayAgreementFromNotify(String externalAgreementNo, String agreementNo, String status) {
        if (externalAgreementNo == null || externalAgreementNo.isBlank()
                || agreementNo == null || agreementNo.isBlank()) {
            return false;
        }
        if (status != null && !status.isBlank()
                && !"NORMAL".equalsIgnoreCase(status)
                && !"SUCCESS".equalsIgnoreCase(status)) {
            log.info("alipay agreement notify ignored status={} external={}", status, externalAgreementNo);
            return false;
        }
        String pendingKey = ALIPAY_PENDING_PREFIX + externalAgreementNo.trim();
        UserInfo preview = userInfoRepository.findByAlipayAgreementId(pendingKey)
                .or(() -> userInfoRepository.findByAlipayAgreementId(externalAgreementNo.trim()))
                .orElse(null);
        if (preview == null) {
            log.warn("alipay agreement notify user not found external={}", externalAgreementNo);
            return false;
        }
        return runWithPayScoreUserLock(preview.getUserId(), () -> {
            UserInfo user = userInfoRepository.findByIdForUpdate(preview.getUserId()).orElse(null);
            if (user == null) {
                return false;
            }
            String current = user.getAlipayAgreementId();
            if (current == null || current.isBlank()
                    || (!current.equals(pendingKey) && !current.equals(externalAgreementNo.trim()))) {
                log.warn("alipay agreement notify stale external={} user={} current={}",
                        externalAgreementNo, user.getUserId(), current);
                return false;
            }
            user.setAlipayAgreementId(agreementNo.trim());
            if (!PayChannels.BALANCE.equalsIgnoreCase(
                    user.getPayPreferredChannel() == null ? "" : user.getPayPreferredChannel().trim())) {
                user.setPayPreferredChannel(PayChannels.ALIPAY);
            }
            userInfoRepository.save(user);
            log.info("alipay agreement bound user={} agreement={} external={}",
                    user.getUserId(), agreementNo, externalAgreementNo);
            return true;
        });
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
        if (PayChannels.ALIPAY.equals(preferred) && isActiveAlipayAgreementId(user.getAlipayAgreementId())) {
            return chargeAlipayAgreement(user, orderId, amountCents, description);
        }
        if (user.isPayscoreEnabled() && user.getPayscoreContractId() != null
                && !user.getPayscoreContractId().isBlank()) {
            return chargeWeChatPayScore(user, orderId, amountCents, description);
        }
        if (isActiveAlipayAgreementId(user.getAlipayAgreementId())) {
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
            try {
                String tradeNo = alipayPayClient.payWithAgreement(
                        orderId, user.getAlipayAgreementId(), amountCents, description);
                log.info("alipay native agreement charge user={} order={} amount={}",
                        user.getUserId(), orderId, amountCents);
                return new ChargeResult(PayChannels.ALIPAY, tradeNo);
            } catch (RuntimeException ex) {
                if (agreementChargeClient.isConfigured()) {
                    log.warn("alipay native charge failed, fallback gateway order={}: {}", orderId, ex.getMessage());
                    String tradeNo = agreementChargeClient.charge(new AgreementChargeClient.ChargeRequest(
                            PayChannels.ALIPAY,
                            user.getUserId(),
                            orderId,
                            user.getAlipayAgreementId(),
                            amountCents,
                            description
                    )).tradeNo();
                    return new ChargeResult(PayChannels.ALIPAY, tradeNo);
                }
                throw ex;
            }
        }
        if (agreementChargeClient.isConfigured() && payScoreProperties.liveChargeEnabled()) {
            String tradeNo = agreementChargeClient.charge(new AgreementChargeClient.ChargeRequest(
                    PayChannels.ALIPAY,
                    user.getUserId(),
                    orderId,
                    user.getAlipayAgreementId(),
                    amountCents,
                    description
            )).tradeNo();
            log.info("alipay gateway agreement charge user={} order={} amount={}",
                    user.getUserId(), orderId, amountCents);
            return new ChargeResult(PayChannels.ALIPAY, tradeNo);
        }
        if (securityProperties.mockEnabled() && payScoreProperties.mockEnabled()) {
            String tradeNo = "MOCK-ALI-" + orderId;
            log.info("alipay agreement mock charge user={} order={} amount={} desc={}",
                    user.getUserId(), orderId, amountCents, description);
            return new ChargeResult(PayChannels.ALIPAY, tradeNo);
        }
        throw new IllegalStateException(
                "Alipay agreement charge unavailable: configure Alipay OpenAPI + PAYSCORE_LIVE_CHARGE_ENABLED, "
                        + "or a charge gateway, or enable mock.");
    }

    private UserInfo requireUser(Long userId) {
        return userInfoRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    static String payScoreUserLockKey(long userId) {
        return "payscore:user:" + userId;
    }

    private <T> T runWithPayScoreUserLock(long userId, Supplier<T> action) {
        String lockKey = payScoreUserLockKey(userId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "免密签约处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    public record ChargeResult(String channel, String tradeNo) {}

    public record AlipaySignResult(boolean active, String contractId, String signFormHtml, boolean pending) {}
}
