package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AgreementChargeClient;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.support.ApiMessages;
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
    private static final String USER_NOT_FOUND = "user not found";


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

    /**
     * 渠道对当前用户是否**已就绪**（余额恒就绪）。这是「显式选择该渠道能否成交」的唯一判据。
     *
     * <p>🔴 与 {@link AccountService#doSetPayPreferredChannel} 的校验必须同源 —— 否则会出现
     * 「设得了优先支付、却扣不动款」或反过来的自相矛盾。故两边都调本方法，不再各写一份条件。
     */
    public static boolean isChannelUsable(UserInfo user, String channel) {
        if (user == null || channel == null) {
            return false;
        }
        String c = channel.trim().toUpperCase(java.util.Locale.ROOT);
        if (PayChannels.BALANCE.equals(c)) {
            return true;
        }
        if (PayChannels.WECHAT.equals(c)) {
            return user.isPayscoreEnabled()
                    && user.getPayscoreContractId() != null
                    && !user.getPayscoreContractId().isBlank();
        }
        if (PayChannels.ALIPAY.equals(c)) {
            return isActiveAlipayAgreementId(user.getAlipayAgreementId());
        }
        return false;
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
                    .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
            // C17: 本地 PSC-* 伪签约仅限 mock 环境；生产（enabled=true 且 mock=false）不得静默落伪单
            if (!securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "微信先享支付签约未接入");
            }
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
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
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

    /** 支付宝协议状态枚举：解约/关闭类（H68）。 */
    private static final java.util.Set<String> ALIPAY_AGREEMENT_TERMINAL_STATES = java.util.Set.of(
            "UNSIGN", "TERMINATE", "TERMINATED", "CLOSE", "CLOSED", "STOP", "STOPPED",
            "CANCEL", "CANCELLED", "REVOKED", "TEMP");

    /** 支付宝协议签约异步通知：用 external_agreement_no 绑定真实 agreement_no。 */
    @Transactional
    public boolean bindAlipayAgreementFromNotify(String externalAgreementNo, String agreementNo, String status) {
        return bindAlipayAgreementFromNotify(externalAgreementNo, agreementNo, status, null);
    }

    /**
     * H68: 支持 (a) 解约/关闭类状态 → 清除用户 alipayAgreementId；
     * (b) notify 携带 alipay_user_id 时与已保存 buyer id 比对，防止 A 用户协议绑到 B 用户
     * （历史数据缺失时保存并放行，见内注释）。
     */
    @Transactional
    public boolean bindAlipayAgreementFromNotify(String externalAgreementNo, String agreementNo,
                                                 String status, String alipayUserId) {
        if (externalAgreementNo == null || externalAgreementNo.isBlank()) {
            // 解约通知可能只带 agreement_no：无 external 时按 agreement_no 找用户
            if (agreementNo == null || agreementNo.isBlank()) {
                return false;
            }
            return handleAgreementReleaseByAgreementNo(agreementNo.trim(), status);
        }
        if (isTerminalAgreementStatus(status)) {
            return handleAgreementRelease(externalAgreementNo.trim(), agreementNo, status);
        }
        if (agreementNo == null || agreementNo.isBlank()) {
            return false;
        }
        if (!"NORMAL".equalsIgnoreCase(status) && !"SUCCESS".equalsIgnoreCase(status)
                && status != null && !status.isBlank()) {
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
            // H68(b): buyer id 校验 —— 已保存且与通知不一致则拒绝绑定；缺失（历史数据）则保存并放行
            if (alipayUserId != null && !alipayUserId.isBlank()) {
                if (user.getAlipayUserId() != null && !user.getAlipayUserId().isBlank()
                        && !user.getAlipayUserId().trim().equals(alipayUserId.trim())) {
                    log.error("alipay agreement notify buyer id mismatch user={} saved={} notify={}",
                            user.getUserId(), user.getAlipayUserId(), alipayUserId);
                    return false;
                }
                if (user.getAlipayUserId() == null || user.getAlipayUserId().isBlank()) {
                    log.warn("alipay agreement notify buyer id missing on user={}, saving from notify user={}",
                            user.getUserId(), user.getUserId());
                    user.setAlipayUserId(alipayUserId.trim());
                }
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

    private static boolean isTerminalAgreementStatus(String status) {
        return status != null && !status.isBlank()
                && ALIPAY_AGREEMENT_TERMINAL_STATES.contains(status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** H68(a): 解约/关闭 → 清除 alipayAgreementId，保留痕迹日志（协议状态无独立列）。 */
    private boolean handleAgreementRelease(String externalAgreementNo, String agreementNo, String status) {
        String pendingKey = ALIPAY_PENDING_PREFIX + externalAgreementNo;
        UserInfo preview = userInfoRepository.findByAlipayAgreementId(pendingKey)
                .or(() -> userInfoRepository.findByAlipayAgreementId(externalAgreementNo))
                .or(() -> agreementNo == null || agreementNo.isBlank()
                        ? java.util.Optional.<UserInfo>empty()
                        : userInfoRepository.findByAlipayAgreementId(agreementNo.trim()))
                .orElse(null);
        if (preview == null) {
            log.warn("alipay agreement release notify user not found external={} status={}",
                    externalAgreementNo, status);
            return false;
        }
        return runWithPayScoreUserLock(preview.getUserId(), () -> doClearAgreement(preview.getUserId(), status));
    }

    private boolean handleAgreementReleaseByAgreementNo(String agreementNo, String status) {
        UserInfo preview = userInfoRepository.findByAlipayAgreementId(agreementNo).orElse(null);
        if (preview == null) {
            log.warn("alipay agreement release notify user not found agreement={} status={}", agreementNo, status);
            return false;
        }
        return runWithPayScoreUserLock(preview.getUserId(), () -> doClearAgreement(preview.getUserId(), status));
    }

    private boolean doClearAgreement(Long userId, String status) {
        UserInfo user = userInfoRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return false;
        }
        if (user.getAlipayAgreementId() != null && !user.getAlipayAgreementId().isBlank()) {
            user.setAlipayAgreementId(null);
            userInfoRepository.save(user);
            // M01：updateById 忽略 null 列，alipay_agreement_id 清列须 wrapper 显式 set(null)
            userInfoRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserInfo>lambdaUpdate()
                    .eq(UserInfo::getUserId, user.getUserId())
                    .set(UserInfo::getAlipayAgreementId, null));
        }
        // 协议状态无独立存储列：以 WARN 日志留痕，供审计/客服检索
        log.warn("alipay agreement released user={} status={} agreementCleared=true", userId, status);
        return true;
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

    /**
     * 显式渠道扣款（消费者在结算页主动选择，F6）：**不做偏好解析、不降级**。
     *
     * <p>与 {@link #charge} 的关键差别：{@code charge} 把「用户偏好 BALANCE」「扫码入口渠道」纳入决策，
     * 选不出可用渠道时返回 {@code BALANCE} 让调用方回落余额；本方法**只认传入渠道** ——
     * 未就绪直接 412，绝不换成别的渠道扣款（用户选了免密却被扣余额，比直接失败更糟）。
     *
     * <p>注意：{@code charge} 里那条「用户偏好为 BALANCE 就无条件返回 BALANCE」的早退分支
     * 对本方法**不适用**，否则默认偏好 BALANCE 的用户永远选不动免密。
     *
     * @throws ResponseStatusException 412 渠道未就绪；400 渠道值非法（非 BALANCE/WECHAT/ALIPAY）
     */
    public ChargeResult chargeExplicit(UserInfo user, String orderId, int amountCents,
                                       String description, String channel) {
        String c = channel == null ? "" : channel.trim().toUpperCase(java.util.Locale.ROOT);
        // 金额非正或显式选余额：无需走渠道，调用方按余额处理
        if (amountCents <= 0 || PayChannels.BALANCE.equals(c)) {
            return new ChargeResult(PayChannels.BALANCE, null);
        }
        if (PayChannels.WECHAT.equals(c) || PayChannels.ALIPAY.equals(c)) {
            if (!isChannelUsable(user, c)) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.PAY_CHANNEL_NOT_READY);
            }
            return PayChannels.WECHAT.equals(c)
                    ? chargeWeChatPayScore(user, orderId, amountCents, description)
                    : chargeAlipayAgreement(user, orderId, amountCents, description);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
    }

    private ChargeResult chargeWeChatPayScore(UserInfo user, String orderId, int amountCents, String description) {
        if (weChatPayProperties.isConfigured() && payScoreProperties.enabled() && payScoreProperties.liveChargeEnabled()) {
            String tradeNo = agreementChargeClient.charge(new AgreementChargeClient.ChargeRequest(
                    PayChannels.WECHAT,
                    user.getUserId(),
                    orderId,
                    user.getPayscoreContractId(),
                    amountCents,
                    description,
                    orderId
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
                // H41(b): 仅「明确未开通/未配置」类确定性异常才允许 fallback 到 gateway；
                // 超时/未知异常直接抛出（调用方保留 CHARGE_PENDING 痕迹），不得盲切二次扣款
                if (isDeterministicNotOpenedFailure(ex) && agreementChargeClient.isConfigured()) {
                    log.warn("alipay native charge failed (deterministic), fallback gateway order={}: {}",
                            orderId, ex.getMessage());
                    String tradeNo = agreementChargeClient.charge(new AgreementChargeClient.ChargeRequest(
                            PayChannels.ALIPAY,
                            user.getUserId(),
                            orderId,
                            user.getAlipayAgreementId(),
                            amountCents,
                            description,
                            orderId
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
                    description,
                    orderId
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

    /**
     * H41(b): 判断是否「明确未开通/未配置」类确定性失败 —— 只有这类失败才允许 fallback gateway。
     * 超时、网络、限流、未知错误一律返回 false（直接置 CHARGE_PENDING 等补偿/人工）。
     */
    static boolean isDeterministicNotOpenedFailure(RuntimeException ex) {
        if (ex instanceof AgreementChargeClient.AgreementNotConfiguredException) {
            return true;
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String upper = msg.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("NOT CONFIGURED")
                || upper.contains("AGREEMENT_NOT_EXIST")
                || upper.contains("USER_AGREEMENT_NOT_EXIST")
                || upper.contains("USER_NOT_SIGN")
                || upper.contains("AGREEMENT_NOT_EFFECTIVE")
                || msg.contains("未签约")
                || msg.contains("协议不存在");
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
