package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.AlipayPayParams;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.common.dto.RechargePrepayResponse;
import com.aicabinet.common.dto.WxPayParams;
import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AlipayNotifyService;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final RechargeOrderMapper rechargeOrderRepository;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final WeChatPayProperties weChatPayProperties;
    private final SecurityProperties securityProperties;
    private final WeChatPayClient weChatPayClient;
    private final WeChatPayV3Signer v3Signer;
    private final WeChatPayNotifyService notifyService;
    private final AlipayPayClient alipayPayClient;
    private final AlipayNotifyService alipayNotifyService;
    private final BalanceLedgerService balanceLedgerService;
    private final SystemConfigService systemConfigService;
    private final NotificationService notificationService;
    private final PayScoreService payScoreService;

    public PaymentService(RechargeOrderMapper rechargeOrderRepository,
                          UserInfoMapper userInfoRepository,
                          UserAccountMapper userAccountRepository,
                          WeChatPayProperties weChatPayProperties,
                          SecurityProperties securityProperties,
                          WeChatPayClient weChatPayClient,
                          WeChatPayV3Signer v3Signer,
                          WeChatPayNotifyService notifyService,
                          AlipayPayClient alipayPayClient,
                          AlipayNotifyService alipayNotifyService,
                          BalanceLedgerService balanceLedgerService,
                          SystemConfigService systemConfigService,
                          NotificationService notificationService,
                          PayScoreService payScoreService) {
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.weChatPayProperties = weChatPayProperties;
        this.securityProperties = securityProperties;
        this.weChatPayClient = weChatPayClient;
        this.v3Signer = v3Signer;
        this.notifyService = notifyService;
        this.alipayPayClient = alipayPayClient;
        this.alipayNotifyService = alipayNotifyService;
        this.balanceLedgerService = balanceLedgerService;
        this.systemConfigService = systemConfigService;
        this.notificationService = notificationService;
        this.payScoreService = payScoreService;
    }

    @Transactional
    public RechargePrepayResponse createRechargePrepay(Long userId, String requestedChannel,
                                                       int amountCents, String requestedIdempotencyKey,
                                                       String clientIp) {
        String channel = PayChannels.normalize(requestedChannel);
        String idempotencyKey = requestedIdempotencyKey.trim();
        RechargeOrder existing = rechargeOrderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.getUserId().equals(userId)
                    || existing.getAmountCents() != amountCents
                    || !existing.getChannel().equals(channel)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.INVALID_REQUEST);
            }
            return toPrepayResponse(existing);
        }
        RechargeOrder order = new RechargeOrder();
        order.setOrderId(BizIds.nextNumeric());
        order.setUserId(userId);
        order.setAmountCents(amountCents);
        order.setChannel(channel);
        order.setStatus("PENDING");
        order.setIdempotencyKey(idempotencyKey);
        rechargeOrderRepository.save(order);

        return switch (channel) {
            case PayChannels.WECHAT -> createWeChatPrepay(order, userId, clientIp);
            case PayChannels.ALIPAY -> createAlipayPrepay(order);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.UNSUPPORTED_CHANNEL);
        };
    }

    private RechargePrepayResponse toPrepayResponse(RechargeOrder order) {
        if (PayChannels.ALIPAY.equals(order.getChannel())) {
            if (alipayPayClient.isConfigured() && "PENDING".equals(order.getStatus())) {
                AlipayPayClient.AlipayPrepayResult prepay = alipayPayClient.createWapPay(
                        order.getOrderId(), order.getAmountCents(), "AI Cabinet Recharge");
                AlipayPayParams alipayPay = new AlipayPayParams(
                        order.getOrderId(), prepay.tradeNo(), prepay.payUrl(), prepay.payFormHtml());
                return new RechargePrepayResponse(
                        order.getChannel(), order.getOrderId(), null, alipayPay,
                        Map.of("orderId", order.getOrderId(), "mode", "live"));
            }
            return new RechargePrepayResponse(order.getChannel(), order.getOrderId(), null,
                    new AlipayPayParams(order.getOrderId(), order.getAlipayTradeNo(), null, null),
                    Map.of("orderId", order.getOrderId(), "mode", "mock"));
        }
        Map<String, String> info = Map.of("orderId", order.getOrderId(), "mode", "mock");
        WxPayParams wxPay = new WxPayParams(String.valueOf(Instant.now().getEpochSecond()),
                UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "prepay_id=" + order.getWxPrepayId(), "RSA", "MOCK_SIGN", null);
        return new RechargePrepayResponse(order.getChannel(), order.getOrderId(), wxPay, null, info);
    }

    private RechargePrepayResponse createWeChatPrepay(RechargeOrder order, Long userId, String clientIp) {
        if (weChatPayProperties.isConfigured()) {
            UserInfo user = userInfoRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
            if (user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.WX_OPENID_NOT_BOUND);
            }
            String prepayId = weChatPayClient.unifiedOrderJsapi(
                    user.getWxOpenId(), order.getOrderId(), order.getAmountCents(),
                    "AI开门柜充值", clientIp);
            order.setWxPrepayId(prepayId);
            rechargeOrderRepository.save(order);

            String timeStamp = String.valueOf(Instant.now().getEpochSecond());
            String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String packageValue = "prepay_id=" + prepayId;
            String paySign = v3Signer.signJsapi(
                    weChatPayProperties.appId(), timeStamp, nonceStr, packageValue,
                    weChatPayProperties.privateKey());
            WxPayParams wxPay = new WxPayParams(timeStamp, nonceStr, packageValue, "RSA", paySign, null);
            return new RechargePrepayResponse(
                    PayChannels.WECHAT,
                    order.getOrderId(),
                    wxPay,
                    null,
                    Map.of("orderId", order.getOrderId(), "mode", "live", "apiVersion", "v3"));
        }

        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
        }
        order.setWxPrepayId("mock_prepay_" + order.getOrderId());
        rechargeOrderRepository.save(order);
        WxPayParams wxPay = new WxPayParams(
                String.valueOf(Instant.now().getEpochSecond()),
                UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "prepay_id=" + order.getWxPrepayId(),
                "RSA",
                "MOCK_SIGN",
                null);
        return new RechargePrepayResponse(
                PayChannels.WECHAT,
                order.getOrderId(),
                wxPay,
                null,
                Map.of("orderId", order.getOrderId(), "mode", "mock", "apiVersion", "v3"));
    }

    private RechargePrepayResponse createAlipayPrepay(RechargeOrder order) {
        if (alipayPayClient.isConfigured()) {
            AlipayPayClient.AlipayPrepayResult prepay = alipayPayClient.createWapPay(
                    order.getOrderId(), order.getAmountCents(), "AI Cabinet Recharge");
            if (prepay.tradeNo() != null && !prepay.tradeNo().isBlank()) {
                order.setAlipayTradeNo(prepay.tradeNo());
            }
            rechargeOrderRepository.save(order);
            AlipayPayParams alipayPay = new AlipayPayParams(order.getOrderId(), prepay.tradeNo(), prepay.payUrl(), prepay.payFormHtml());
            return new RechargePrepayResponse(
                    PayChannels.ALIPAY,
                    order.getOrderId(),
                    null,
                    alipayPay,
                    Map.of("orderId", order.getOrderId(), "mode", "live"));
        }

        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.ALIPAY_PAY_NOT_CONFIGURED);
        }
        order.setAlipayTradeNo("mock_alipay_" + order.getOrderId());
        rechargeOrderRepository.save(order);
        AlipayPayParams alipayPay = new AlipayPayParams(
                order.getOrderId(),
                order.getAlipayTradeNo(),
                null,
                null);
        return new RechargePrepayResponse(
                PayChannels.ALIPAY,
                order.getOrderId(),
                null,
                alipayPay,
                Map.of("orderId", order.getOrderId(), "mode", "mock"));
    }

    @Transactional
    public RechargeOrderDto confirmRechargeMock(Long userId, String orderId) {
        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        RechargeOrder order = requireOwnedOrder(userId, orderId);
        creditRecharge(order);
        return toDto(order);
    }

    @Transactional
    public void handleWeChatNotify(String body,
                                   String timestamp,
                                   String nonce,
                                   String signature,
                                   String serial) {
        JsonNode transaction = notifyService.parseAndVerify(body, timestamp, nonce, signature, serial);
        if (!"SUCCESS".equals(transaction.path("trade_state").asText())) {
            log.info("wechat notify ignored trade_state={}", transaction.path("trade_state").asText());
            return;
        }
        String outTradeNo = transaction.path("out_trade_no").asText(null);
        if (outTradeNo == null) {
            throw new IllegalArgumentException(ApiMessages.MISSING_OUT_TRADE_NO);
        }
        rechargeOrderRepository.findById(outTradeNo).ifPresent(order -> {
            if (!PayChannels.WECHAT.equalsIgnoreCase(order.getChannel())) {
                log.warn("wechat notify channel mismatch orderId={} channel={}", outTradeNo, order.getChannel());
                return;
            }
            if (!"PAID".equals(order.getStatus())) {
                String txnId = transaction.path("transaction_id").asText(null);
                if (txnId != null && !txnId.isBlank()) {
                    order.setWxTransactionId(txnId);
                }
                creditRecharge(order);
            }
        });
    }

    @Transactional
    public void handleAlipayNotify(Map<String, String> params) {
        Map<String, String> verified = alipayNotifyService.parseAndVerify(params);
        if (isAlipayAgreementNotify(verified)) {
            String external = firstNonBlank(verified.get("external_agreement_no"), verified.get("external_sign_no"));
            String agreementNo = verified.get("agreement_no");
            String status = firstNonBlank(verified.get("status"), verified.get("agreement_status"));
            boolean ok = payScoreService.bindAlipayAgreementFromNotify(external, agreementNo, status);
            log.info("alipay agreement notify handled ok={} external={} agreement={}", ok, external, agreementNo);
            return;
        }
        String tradeStatus = verified.get("trade_status");
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            log.info("alipay notify ignored trade_status={}", tradeStatus);
            return;
        }
        String outTradeNo = verified.get("out_trade_no");
        if (outTradeNo == null || outTradeNo.isBlank()) {
            throw new IllegalArgumentException(ApiMessages.MISSING_OUT_TRADE_NO);
        }
        rechargeOrderRepository.findById(outTradeNo).ifPresent(order -> {
            if (!PayChannels.ALIPAY.equalsIgnoreCase(order.getChannel())) {
                log.warn("alipay notify channel mismatch orderId={} channel={}", outTradeNo, order.getChannel());
                return;
            }
            if (!"PAID".equals(order.getStatus())) {
                String tradeNo = verified.get("trade_no");
                if (tradeNo != null && !tradeNo.isBlank()) {
                    order.setAlipayTradeNo(tradeNo);
                }
                creditRecharge(order);
            }
        });
    }

    private static boolean isAlipayAgreementNotify(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        String notifyType = params.get("notify_type");
        if (notifyType != null && notifyType.toLowerCase().contains("agreement")) {
            return true;
        }
        String agreementNo = params.get("agreement_no");
        if (agreementNo == null || agreementNo.isBlank()) {
            return false;
        }
        // 签约通知通常无 trade_status / out_trade_no，或带 external_agreement_no
        String tradeStatus = params.get("trade_status");
        String outTradeNo = params.get("out_trade_no");
        String external = params.get("external_agreement_no");
        if (external != null && !external.isBlank() && (tradeStatus == null || tradeStatus.isBlank())) {
            return true;
        }
        return (outTradeNo == null || outTradeNo.isBlank()) && (tradeStatus == null || tradeStatus.isBlank());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public PageResult<RechargeOrderDto> listMyRecharges(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<RechargeOrder> result = rechargeOrderRepository.search(null, userId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(PaymentService::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional
    public RechargeOrderDto getRechargeOrder(Long userId, String orderId) {
        RechargeOrder order = requireOwnedOrder(userId, orderId);
        syncPendingOrder(order);
        return toDto(order);
    }

    @Transactional
    public RechargeOrderDto cancelRecharge(Long userId, String orderId) {
        RechargeOrder order = requireOwnedOrder(userId, orderId);
        if (!"PENDING".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(order.getChannel())) {
            cancelPendingAlipay(order);
        } else {
            cancelPendingWeChat(order);
        }
        order.setStatus("CANCELLED");
        rechargeOrderRepository.save(order);
        log.info("recharge cancelled orderId={}", orderId);
        return toDto(order);
    }

    /**
     * 超时未支付的充值单自动取消（先向渠道关单/同步，避免已支付漏入账）。
     */
    @Transactional
    public int autoCancelExpiredPending() {
        int minutes = systemConfigService.getInt(SystemConfigService.RECHARGE_AUTO_CANCEL_MINUTES, 30);
        if (minutes <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        List<RechargeOrder> expired = rechargeOrderRepository.findByStatusAndCreatedAtBefore("PENDING", cutoff);
        int n = 0;
        for (RechargeOrder order : expired) {
            try {
                syncPendingOrder(order);
                if (!"PENDING".equals(order.getStatus())) {
                    continue;
                }
                if (PayChannels.ALIPAY.equalsIgnoreCase(order.getChannel())) {
                    cancelPendingAlipay(order);
                } else {
                    cancelPendingWeChat(order);
                }
                if ("PAID".equals(order.getStatus())) {
                    continue;
                }
                order.setStatus("CANCELLED");
                rechargeOrderRepository.save(order);
                n++;
            } catch (Exception ex) {
                log.warn("auto cancel recharge failed orderId={}", order.getOrderId(), ex);
            }
        }
        if (n > 0) {
            log.info("auto cancelled pending recharge orders count={} minutes={}", n, minutes);
        }
        return n;
    }

    @Transactional
    public RechargeOrderDto refundRecharge(String orderId, String reason) {
        RechargeOrder order = rechargeOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!"PAID".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PAID);
        }
        UserAccount account = userAccountRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < order.getAmountCents()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.INSUFFICIENT_REFUND);
        }

        if (PayChannels.ALIPAY.equalsIgnoreCase(order.getChannel())) {
            refundAlipay(order, reason);
        } else {
            refundWeChat(order, reason);
        }

        balanceLedgerService.change(order.getUserId(), -order.getAmountCents(),
                "RECHARGE_REFUND", order.getOrderId(), "recharge-refund:" + order.getOrderId(),
                reason == null || reason.isBlank() ? "充值退款" : reason);

        order.setRefundedCents(order.getAmountCents());
        order.setStatus("REFUNDED");
        order.setRefundedAt(Instant.now());
        rechargeOrderRepository.save(order);
        log.info("recharge refunded orderId={} user={} amount={}", orderId, order.getUserId(), order.getAmountCents());
        return toDto(order);
    }

    private void cancelPendingWeChat(RechargeOrder order) {
        if (!weChatPayProperties.isConfigured()) {
            return;
        }
        try {
            weChatPayClient.closeOrder(order.getOrderId());
        } catch (Exception e) {
            log.warn("wechat close order failed orderId={}, trying query sync", order.getOrderId(), e);
            syncPendingFromWeChat(order);
            if ("PAID".equals(order.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_PAID);
            }
        }
    }

    private void cancelPendingAlipay(RechargeOrder order) {
        if (!alipayPayClient.isConfigured()) {
            return;
        }
        try {
            alipayPayClient.closeOrder(order.getOrderId());
        } catch (Exception e) {
            log.warn("alipay close order failed orderId={}, trying query sync", order.getOrderId(), e);
            syncPendingFromAlipay(order);
            if ("PAID".equals(order.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_PAID);
            }
        }
    }

    private void refundWeChat(RechargeOrder order, String reason) {
        refundWeChatPartial(order, order.getAmountCents(), reason, null);
    }

    private void refundAlipay(RechargeOrder order, String reason) {
        refundAlipayPartial(order, order.getAmountCents(), reason, null);
    }

    /**
     * 充值单部分/全额原路退款（仅渠道侧 + 更新 refunded_cents；余额扣减由调用方负责）。
     * @return 微信/支付宝退款商户退款单号
     */
    @Transactional
    public String refundRechargeChannelPartial(String orderId, int refundCents, String reason, String outRefundNo) {
        if (refundCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        RechargeOrder order = rechargeOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!"PAID".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PAID);
        }
        int already = Math.max(0, order.getRefundedCents());
        int refundable = order.getAmountCents() - already;
        if (refundCents > refundable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "充值单可退金额不足（剩余 ¥" + String.format("%.2f", refundable / 100.0) + "）");
        }
        String refundNo = outRefundNo == null || outRefundNo.isBlank()
                ? "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase()
                : outRefundNo.trim();
        if (PayChannels.ALIPAY.equalsIgnoreCase(order.getChannel())) {
            refundAlipayPartial(order, refundCents, reason, refundNo);
        } else {
            refundWeChatPartial(order, refundCents, reason, refundNo);
        }
        order.setRefundedCents(already + refundCents);
        if (order.getRefundedCents() >= order.getAmountCents()) {
            order.setStatus("REFUNDED");
            order.setRefundedAt(Instant.now());
        }
        rechargeOrderRepository.save(order);
        log.info("recharge channel partial refund orderId={} amount={} totalRefunded={}",
                orderId, refundCents, order.getRefundedCents());
        return refundNo;
    }

    private void refundWeChatPartial(RechargeOrder order, int refundCents, String reason, String outRefundNo) {
        if (weChatPayProperties.isConfigured()) {
            String no = outRefundNo == null || outRefundNo.isBlank()
                    ? "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase()
                    : outRefundNo;
            weChatPayClient.createRefund(
                    order.getOrderId(), no, refundCents, order.getAmountCents(), reason);
        } else if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
        }
    }

    private void refundAlipayPartial(RechargeOrder order, int refundCents, String reason, String outRefundNo) {
        if (alipayPayClient.isConfigured()) {
            String no = outRefundNo == null || outRefundNo.isBlank()
                    ? "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase()
                    : outRefundNo;
            alipayPayClient.refund(order.getOrderId(), no, refundCents, reason);
        } else if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.ALIPAY_PAY_NOT_CONFIGURED);
        }
    }

    private RechargeOrder requireOwnedOrder(Long userId, String orderId) {
        RechargeOrder order = rechargeOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ORDER_ACCESS_DENIED);
        }
        return order;
    }

    private void syncPendingOrder(RechargeOrder order) {
        if (!"PENDING".equals(order.getStatus())) {
            return;
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(order.getChannel())) {
            syncPendingFromAlipay(order);
        } else {
            syncPendingFromWeChat(order);
        }
    }

    private void syncPendingFromWeChat(RechargeOrder order) {
        if (!weChatPayProperties.isConfigured()) {
            return;
        }
        try {
            JsonNode remote = weChatPayClient.queryByOutTradeNo(order.getOrderId());
            String tradeState = remote.path("trade_state").asText("");
            if ("SUCCESS".equals(tradeState)) {
                String txnId = remote.path("transaction_id").asText(null);
                if (txnId != null && !txnId.isBlank()) {
                    order.setWxTransactionId(txnId);
                }
                creditRecharge(order);
            } else if ("CLOSED".equals(tradeState) || "REVOKED".equals(tradeState) || "PAYERROR".equals(tradeState)) {
                order.setStatus("CANCELLED");
                rechargeOrderRepository.save(order);
            }
        } catch (Exception e) {
            log.debug("wechat query sync skipped orderId={}: {}", order.getOrderId(), e.getMessage());
        }
    }

    private void syncPendingFromAlipay(RechargeOrder order) {
        if (!alipayPayClient.isConfigured()) {
            return;
        }
        try {
            // Alipay query shape varies by SDK wrapper; reuse existing client
            var remote = alipayPayClient.queryByOutTradeNo(order.getOrderId());
            String tradeStatus = remote.path("trade_status").asText("");
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                String tradeNo = remote.path("trade_no").asText(null);
                if (tradeNo != null && !tradeNo.isBlank()) {
                    order.setAlipayTradeNo(tradeNo);
                }
                creditRecharge(order);
            } else if ("TRADE_CLOSED".equals(tradeStatus)) {
                order.setStatus("CANCELLED");
                rechargeOrderRepository.save(order);
            }
        } catch (Exception e) {
            log.debug("alipay query sync skipped orderId={}: {}", order.getOrderId(), e.getMessage());
        }
    }

    private static RechargeOrderDto toDto(RechargeOrder order) {
        return new RechargeOrderDto(
                order.getOrderId(),
                order.getUserId(),
                order.getAmountCents(),
                order.getChannel(),
                order.getStatus(),
                order.getWxPrepayId(),
                order.getWxTransactionId(),
                order.getAlipayTradeNo(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getRefundedAt()
        );
    }

    private void creditRecharge(RechargeOrder order) {
        if ("PAID".equals(order.getStatus())) return;
        if (!"PENDING".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
        }
        var operation = balanceLedgerService.change(order.getUserId(), order.getAmountCents(),
                "RECHARGE", order.getOrderId(), "recharge-credit:" + order.getOrderId(),
                "充值到账（灰度环境测试余额）");
        order.setStatus("PAID");
        order.setPaidAt(Instant.now());
        order.setPaymentOperationId(operation.getOperationId());
        rechargeOrderRepository.save(order);
        try {
            notificationService.notifyConsumer(
                    order.getUserId(),
                    "recharge_success",
                    Map.of("amount", yuan(order.getAmountCents())),
                    "RECHARGE",
                    order.getOrderId());
        } catch (Exception e) {
            log.warn("recharge notification failed orderId={}", order.getOrderId(), e);
        }
        log.info("recharge credited user={} amount={} channel={}",
                order.getUserId(), order.getAmountCents(), order.getChannel());
    }

    private static String yuan(int cents) {
        return java.math.BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }
}
