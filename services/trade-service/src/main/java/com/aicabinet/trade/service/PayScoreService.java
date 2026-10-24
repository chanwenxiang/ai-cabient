package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.PayScoreOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.PayScoreOrderMapper;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final com.aicabinet.trade.payment.WeChatPayScoreClient weChatPayScoreClient;
    private final com.aicabinet.trade.mapper.PayScoreOrderMapper payScoreOrderMapper;
    private final com.aicabinet.trade.mapper.CabinetOrderLineMapper cabinetOrderLineMapper;

    public PayScoreService(PayScoreProperties payScoreProperties,
                           SecurityProperties securityProperties,
                           WeChatPayProperties weChatPayProperties,
                           UserInfoMapper userInfoRepository,
                           AlipayPayClient alipayPayClient,
                           AgreementChargeClient agreementChargeClient,
                           DistributedLockService distributedLockService,
                           com.aicabinet.trade.payment.WeChatPayScoreClient weChatPayScoreClient,
                           com.aicabinet.trade.mapper.PayScoreOrderMapper payScoreOrderMapper,
                           com.aicabinet.trade.mapper.CabinetOrderLineMapper cabinetOrderLineMapper) {
        this.payScoreProperties = payScoreProperties;
        this.securityProperties = securityProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.userInfoRepository = userInfoRepository;
        this.alipayPayClient = alipayPayClient;
        this.agreementChargeClient = agreementChargeClient;
        this.distributedLockService = distributedLockService;
        this.weChatPayScoreClient = weChatPayScoreClient;
        this.payScoreOrderMapper = payScoreOrderMapper;
        this.cabinetOrderLineMapper = cabinetOrderLineMapper;
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
    /** 微信支付分签约结果：pending=true 表示待用户在授权页确认（activation 走回调）。 */
    public record WechatSignResult(boolean pending, String contractId, String applyPermissionsToken) {
    }

    /** 微信待激活标记前缀（后面紧跟商户侧 authorization_code；激活时整列替换为 code 本体）。 */
    public static final String WECHAT_PENDING_PREFIX = "PENDING:PSU-";
    private static final String WECHAT_OPEN_SERVICE = "USER_OPEN_SERVICE";
    private static final String WECHAT_CLOSE_SERVICE = "USER_CLOSE_SERVICE";

    /**
     * 微信支付分签约（先享后付）。
     *
     * <p>三条路径（CB-064）：
     * ① {@code aicabinet.payscore.enabled + service-id} 已配置且微信支付已配置 → 走真实
     * 商户预授权：返回 {@code apply_permissions_token} 给小程序拉起授权页，用户确认后由
     * 回调（{@link #handleWechatPermissionNotify}）落 {@code authorization_code} 激活；
     * ② 未配置但 mock 环境 → 本地伪签约（C17：仅限 mock，生产不得静默落伪单）；
     * ③ 未配置且非 mock → 501 fail-closed（准入现实：直连免确认模式灰度中）。</p>
     */
    public WechatSignResult signWeChatPayScore(Long userId) {
        return runWithPayScoreUserLock(userId, () -> {
            // 闸前置：未配置（或 mock 未开）时不动任何数据——fail-closed 先于 DB 访问
            boolean realReady = payScoreProperties.enabled() && weChatPayScoreClient.isContractReady()
                    && !securityProperties.mockEnabled();
            if (!realReady && !securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "微信先享支付签约未接入");
            }
            UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
            if (realReady) {
                return signWeChatPayScoreReal(user);
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
            return new WechatSignResult(false, contractId, null);
        });
    }

    /**
     * 真实签约：商户预授权（POST /v3/payscore/permissions）。用户此刻**未开通**——落
     * {@code PENDING:PSU-*} 标记、enabled=false，激活只认回调里的 {@code authorization_code}；
     * 免密就绪判定（{@link #isPasswordFreeReadyForChannel}）按 enabled 位自然不会误放行。
     */
    private WechatSignResult signWeChatPayScoreReal(UserInfo user) {
        String openid = user.getWxOpenId();
        if (openid == null || openid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "请先用微信登录小程序后再开通微信免密");
        }
        String authorizationCode = newAuthorizationCode();
        com.fasterxml.jackson.databind.JsonNode resp =
                weChatPayScoreClient.createPermission(authorizationCode);
        String token = resp == null ? "" : resp.path("apply_permissions_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("微信支付分签约响应缺少 apply_permissions_token");
        }
        String pendingMarker = WECHAT_PENDING_PREFIX + authorizationCode;
        user.setPayscoreEnabled(false);
        user.setPayscoreContractId(pendingMarker);
        userInfoRepository.save(user);
        log.info("wechat payscore permission requested user={} code={}", user.getUserId(),
                maskAuthorizationCode(authorizationCode));
        return new WechatSignResult(true, pendingMarker, token);
    }

    /** 商户侧授权协议号：≤32 字符，仅数字/字母/`-`（微信契约）；每次签约尝试一个新号。 */
    private static String newAuthorizationCode() {
        return "PSU" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /**
     * 微信支付分授权/解约回调（CB-064）。{@code user_service_status}：
     * {@code USER_OPEN_SERVICE} 激活、{@code USER_CLOSE_SERVICE} 停用；未知状态只记 WARN
     * （fail-closed，不猜测）。激活按 {@code PENDING:PSU-<authorization_code>} 定位，
     * 标记已被重试覆盖时按 openid 兜底。事件体与预期不符（无主回调）只记 WARN 不抛错：
     * 返回 204 让微信停止重试，避免无限重推。
     */
    @Transactional
    public void handleWechatPermissionNotify(com.fasterxml.jackson.databind.JsonNode event) {
        String authorizationCode = event.path("authorization_code").asText("");
        String status = event.path("user_service_status").asText("");
        if (authorizationCode.isBlank()) {
            log.warn("wechat payscore notify without authorization_code keys={}", fieldNamesOf(event));
            return;
        }
        if (WECHAT_CLOSE_SERVICE.equals(status)) {
            deactivateWechatPayscore(authorizationCode);
            return;
        }
        if (!WECHAT_OPEN_SERVICE.equals(status)) {
            log.warn("wechat payscore notify unknown user_service_status={} code={} keys={}",
                    status, maskAuthorizationCode(authorizationCode), fieldNamesOf(event));
            return;
        }
        activateWechatPayscore(event, authorizationCode);
    }

    private void activateWechatPayscore(com.fasterxml.jackson.databind.JsonNode event, String authorizationCode) {
        UserInfo user = findByPayscoreContractId(WECHAT_PENDING_PREFIX + authorizationCode);
        if (user == null) {
            // 回调可能晚于用户重试签约（旧 pending 标记已被新 code 覆盖）：按 openid 兜底定位
            String openid = event.path("openid").asText("");
            if (!openid.isBlank()) {
                user = userInfoRepository.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserInfo>()
                                .eq(UserInfo::getWxOpenId, openid)).stream().findFirst().orElse(null);
            }
        }
        if (user == null) {
            log.warn("wechat payscore notify unmatched code={} keys={}",
                    maskAuthorizationCode(authorizationCode), fieldNamesOf(event));
            return;
        }
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId(authorizationCode);
        if (!PayChannels.BALANCE.equalsIgnoreCase(
                user.getPayPreferredChannel() == null ? "" : user.getPayPreferredChannel().trim())) {
            user.setPayPreferredChannel(PayChannels.WECHAT);
        }
        userInfoRepository.save(user);
        log.info("wechat payscore authorized user={} code={}", user.getUserId(),
                maskAuthorizationCode(authorizationCode));
    }

    private void deactivateWechatPayscore(String authorizationCode) {
        UserInfo user = findByPayscoreContractId(authorizationCode);
        if (user == null) {
            log.warn("wechat payscore close unmatched code={}", maskAuthorizationCode(authorizationCode));
            return;
        }
        user.setPayscoreEnabled(false);
        user.setPayscoreContractId(null);
        if (PayChannels.WECHAT.equalsIgnoreCase(
                user.getPayPreferredChannel() == null ? "" : user.getPayPreferredChannel().trim())) {
            user.setPayPreferredChannel(PayChannels.BALANCE);
        }
        userInfoRepository.save(user);
        // M01：save/updateById 忽略 null 列 ⇒ 协议号列必须用 wrapper 显式 set(null)
        userInfoRepository.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserInfo>lambdaUpdate()
                        .eq(UserInfo::getUserId, user.getUserId())
                        .set(UserInfo::getPayscoreContractId, null));
        log.info("wechat payscore closed user={} code={}", user.getUserId(),
                maskAuthorizationCode(authorizationCode));
    }

    private UserInfo findByPayscoreContractId(String contractId) {
        return userInfoRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserInfo>()
                        .eq(UserInfo::getPayscoreContractId, contractId))
                .stream().findFirst().orElse(null);
    }

    private static String maskAuthorizationCode(String code) {
        return code.substring(0, Math.min(6, code.length())) + "***";
    }

    private static String fieldNamesOf(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new java.util.LinkedHashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    /** 真实免确认渠道是否可用（enabled + 微信支付已配置 + serviceId 已下发，且非 mock）。 */
    public boolean wechatRealChannelReady() {
        return payScoreProperties.enabled()
                && weChatPayScoreClient.isContractReady()
                && !securityProperties.mockEnabled();
    }

    /** 该会话是否存在未完结的支付分开门单（结算走「完结+异步扣款」路径的判据）。 */
    public boolean hasOpenWechatOrderForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Long n = payScoreOrderMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aicabinet.trade.domain.PayScoreOrder>()
                        .eq(com.aicabinet.trade.domain.PayScoreOrder::getOutOrderNo, sessionId)
                        .in(com.aicabinet.trade.domain.PayScoreOrder::getOrderState, List.of("CREATED", "DOING")));
        return n != null && n > 0;
    }

    /**
     * 开门时创建支付分订单（免确认模式，CB-064 四步之②）：风险金=预授权口径同源
     * （{@code resolvePreauthCents}），评估不通过=不可使用服务 ⇒ 412 拒绝开门（官方语义）。
     *
     * <p>仅真实渠道 + 用户已激活微信免密时生效；其余形态静默返回（走既有同步代扣/余额路径）。
     * 幂等：同会话（out_order_no=sessionId）已创单直接返回。</p>
     */
    @Transactional
    public void openServiceOrderForShopping(ShoppingSession session, int riskAmountCents) {
        if (session == null || !wechatRealChannelReady()) {
            return;
        }
        UserInfo user = userInfoRepository.findById(session.getUserId()).orElse(null);
        if (user == null || !isPasswordFreeReadyForChannel(user, PayChannels.WECHAT)) {
            return;
        }
        String openid = user.getWxOpenId();
        if (openid == null || openid.isBlank()) {
            return;
        }
        if (hasOpenWechatOrderForSession(session.getSessionId())) {
            return;
        }
        try {
            var resp = weChatPayScoreClient.createServiceOrder(session.getSessionId(), openid,
                    "开门购物", riskAmountCents, java.time.Instant.now(),
                    payScoreProperties.permissionNotifyUrl());
            var po = new com.aicabinet.trade.domain.PayScoreOrder();
            po.setOutOrderNo(session.getSessionId());
            po.setUserId(session.getUserId());
            po.setOpenId(openid);
            po.setServiceId(payScoreProperties.serviceId());
            po.setServiceStartTime(java.time.Instant.now());
            po.setTotalAmountCents(riskAmountCents);
            String state = resp == null ? "" : resp.path("state").asText("");
            po.setOrderState(state.isBlank() ? "CREATED" : state);
            po.setNeedUserConfirm(false);
            payScoreOrderMapper.insert(po);
            log.info("wechat payscore service order created session={} risk={}", session.getSessionId(), riskAmountCents);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("wechat payscore create service order failed session={} err={}",
                    session.getSessionId(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "微信免密确认失败，请改用余额支付或稍后再试");
        }
    }

    /**
     * 结算时完结支付分订单（CB-064 四步之④）：{@code post_payments} 逐件「商品信息」
     * （单价×数量，与开门前价目同源同价——CB-063 结论 3），优惠以负向金额单列。
     * 完结后微信进入 {@code USER_PAYING} 异步扣款+自动催收；订单由调用方置 PENDING，
     * 回调 {@code DONE} 才置 PAID。完结失败向上抛（会话冻结→超时清扫→人工），本地留
     * {@code orderState=COMPLETE_FAILED} 痕迹供补偿。
     */
    @Transactional
    public void completeWechatOrderForSession(String sessionId, com.aicabinet.trade.domain.CabinetOrder order) {
        var po = payScoreOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aicabinet.trade.domain.PayScoreOrder>()
                        .eq(com.aicabinet.trade.domain.PayScoreOrder::getOutOrderNo, sessionId)
                        .in(com.aicabinet.trade.domain.PayScoreOrder::getOrderState, List.of("CREATED", "DOING")))
                .stream().findFirst().orElse(null);
        if (po == null) {
            throw new IllegalStateException("wechat payscore order missing for session " + sessionId);
        }
        List<Map<String, Object>> postPayments = new java.util.ArrayList<>();
        int lineSum = 0;
        for (com.aicabinet.trade.domain.CabinetOrderLine line : cabinetOrderLineMapper
                .selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aicabinet.trade.domain.CabinetOrderLine>()
                        .eq(com.aicabinet.trade.domain.CabinetOrderLine::getOrderId, order.getOrderId()))) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", "商品信息");
            p.put("amount", line.getUnitPriceCents());
            p.put("description", line.getSkuName());
            p.put("count", line.getQuantity());
            postPayments.add(p);
            lineSum += line.getLineAmountCents();
        }
        // 券/会员优惠：负向单列（官方 post_discounts），保证逐件明细与应付总额自洽
        int discount = Math.max(0, lineSum - order.getTotalAmountCents());
        List<Map<String, Object>> postDiscounts = discount > 0
                ? List.of(Map.of("name", "优惠", "amount", -discount))
                : null;
        weChatPayScoreClient.completeServiceOrder(sessionId, postPayments, postDiscounts,
                order.getTotalAmountCents(), po.getServiceStartTime(), java.time.Instant.now());
        po.setOrderState("USER_PAYING");
        po.setOrderId(order.getOrderId());
        po.setActualAmountCents(order.getTotalAmountCents());
        po.setServiceEndTime(java.time.Instant.now());
        payScoreOrderMapper.updateById(po);
        log.info("wechat payscore order completed session={} order={} amount={}",
                sessionId, order.getOrderId(), order.getTotalAmountCents());
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

    /**
     * G9：**用户主动解约**（关闭免密代扣）。
     *
     * <p>为什么必须有这个入口：在此之前，微信支付分免密只能开不能关，支付宝免密只能靠渠道侧
     * 通知回调（{@link #bindAlipayAgreementFromNotify} → {@link #doClearAgreement}）才会解除 ——
     * 用户**没有撤回授权的通道**，这是合规缺口，不是可选功能。
     *
     * <p>行为契约：
     * <ul>
     *   <li><b>幂等</b>：没有已生效/待生效合约时返回 {@code terminated=false}，不抛错、不写库；</li>
     *   <li><b>fail-closed（非 mock 一律 501）</b>：渠道侧解约接口尚未接入时，宁可报错也不本地清列 ——
     *       本地清了而渠道侧仍持协议，用户会以为已解约、实际继续被扣款，比直接失败更糟。
     *       口径与 {@link #signWeChatPayScore} 的 C17「生产不得静默落伪单」对称；</li>
     *   <li><b>偏好回落</b>：优先支付渠道若指向被解约的渠道，回落 {@code BALANCE}，不留悬空偏好；</li>
     *   <li><b>待签约也撤</b>：支付宝 {@code PENDING:EXT-*} 尚未激活，但 external_agreement_no 已被占用，
     *       同样要能撤销；</li>
     *   <li>与签约/回调共用同一把用户锁（{@code payscore:user:<id>}），避免并发交叉。</li>
     * </ul>
     */
    @Transactional
    public ContractCancelResult cancelPasswordFree(Long userId) {
        return runWithPayScoreUserLock(userId, () -> doCancelPasswordFree(userId));
    }

    private ContractCancelResult doCancelPasswordFree(Long userId) {
        UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        boolean wechatSigned = user.isPayscoreEnabled()
                && user.getPayscoreContractId() != null && !user.getPayscoreContractId().isBlank();
        boolean alipaySigned = isActiveAlipayAgreementId(user.getAlipayAgreementId());
        boolean alipayPending = pendingExternalNo(user.getAlipayAgreementId()) != null;
        if (!wechatSigned && !alipaySigned && !alipayPending) {
            return new ContractCancelResult(false, java.util.List.of());
        }
        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "渠道侧解约接口未接入；为避免本地已解约而渠道仍可扣款，暂不提供自助解约");
        }
        java.util.List<String> cancelled = new java.util.ArrayList<>();
        if (wechatSigned) {
            user.setPayscoreEnabled(false);
            cancelled.add(PayChannels.WECHAT);
        }
        if (alipaySigned || alipayPending) {
            cancelled.add(PayChannels.ALIPAY);
        }
        String preferred = user.getPayPreferredChannel();
        if (preferred != null
                && cancelled.contains(preferred.trim().toUpperCase(java.util.Locale.ROOT))) {
            user.setPayPreferredChannel(PayChannels.BALANCE);
        }
        user.setPayscoreContractId(null);
        user.setAlipayAgreementId(null);
        userInfoRepository.save(user);
        // M01：updateById 忽略 null 列 ⇒ 两个协议号列必须用 wrapper 显式 set(null)，
        // 否则 DB 里仍留着旧协议号（下次结算/对账会按"已签约"处理）。
        userInfoRepository.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserInfo>lambdaUpdate()
                        .eq(UserInfo::getUserId, user.getUserId())
                        .set(UserInfo::getPayscoreContractId, null)
                        .set(UserInfo::getAlipayAgreementId, null));
        // 协议状态无独立存储列：以 WARN 留痕，供审计/客服检索（与 doClearAgreement 同口径）
        log.warn("pay contract cancelled by user={} channels={} source=self-service", userId, cancelled);
        return new ContractCancelResult(true, java.util.List.copyOf(cancelled));
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

    /** G9：用户主动解约的结果 —— 是否真的解掉了（幂等时为 false）、解掉了哪些渠道。 */
    public record ContractCancelResult(boolean terminated, java.util.List<String> channels) {}

    public record AlipaySignResult(boolean active, String contractId, String signFormHtml, boolean pending) {}
}
