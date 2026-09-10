package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.architecture.AllowTransactionalRemote;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.util.BizIds;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderPaymentService {
    private static final String LITERAL = "（模拟支付退回余额）";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String REFUND = "REFUND";
    private static final String CHARGE = "CHARGE";
    private static final String ADJUST_CHARGE = "ADJUST_CHARGE";


    private static final Logger log = LoggerFactory.getLogger(OrderPaymentService.class);

    private final UserInfoMapper userInfoRepository;
    private final BalanceLedgerService balanceLedgerService;
    private final CheckoutProperties checkoutProperties;
    private final PayScoreService payScoreService;
    private final WeChatPayClient weChatPayClient;
    private final AlipayPayClient alipayPayClient;
    private final WeChatPayProperties weChatPayProperties;
    private final SecurityProperties securityProperties;
    private final PaymentOperationMapper paymentOperationRepository;
    private final CabinetOrderMapper cabinetOrderRepository;
    private final DistributedLockService distributedLockService;
    private final ShoppingSessionMapper sessionRepository;
    private final ConsumerPreauthService consumerPreauthService;
    private final MemberService memberService;
    private final OrderPaymentService self;

    public OrderPaymentService(UserInfoMapper userInfoRepository,
                               PayScoreService payScoreService,
                               WeChatPayClient weChatPayClient,
                               AlipayPayClient alipayPayClient,
                               WeChatPayProperties weChatPayProperties,
                               SecurityProperties securityProperties,
                               PaymentOperationMapper paymentOperationRepository,
                               CabinetOrderMapper cabinetOrderRepository,
                               DistributedLockService distributedLockService,
                               BalanceLedgerService balanceLedgerService,
                               CheckoutProperties checkoutProperties,
                               ShoppingSessionMapper sessionRepository,
                               ConsumerPreauthService consumerPreauthService,
                               @Lazy MemberService memberService,
                               @Lazy OrderPaymentService self) {
        this.userInfoRepository = userInfoRepository;
        this.payScoreService = payScoreService;
        this.weChatPayClient = weChatPayClient;
        this.alipayPayClient = alipayPayClient;
        this.weChatPayProperties = weChatPayProperties;
        this.securityProperties = securityProperties;
        this.paymentOperationRepository = paymentOperationRepository;
        this.cabinetOrderRepository = cabinetOrderRepository;
        this.distributedLockService = distributedLockService;
        this.balanceLedgerService = balanceLedgerService;
        this.checkoutProperties = checkoutProperties;
        this.sessionRepository = sessionRepository;
        this.consumerPreauthService = consumerPreauthService;
        this.memberService = memberService;
        this.self = self;
    }

    /**
     * 订单扣款：须参与调用方事务，以便结算/争议确认中「先落单再扣款」可见未提交订单。
     * PayScore 渠道 HTTP 刻意保留在同事务内（见 {@link AllowTransactionalRemote}）。
     */
    @Transactional
    @AllowTransactionalRemote(reason = "chargeOrder 须与未提交订单同事务可见；PayScore 与落单不可拆")
    public void chargeOrder(CabinetOrder order) {
        if (order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            order.setPayChannel(PayChannels.BALANCE);
            return;
        }
        if (order.getTotalAmountCents() <= 0) {
            order.setPayChannel(PayChannels.BALANCE);
            releaseSessionPreauth(order);
            return;
        }
        runWithOrderPaymentLock(order.getOrderId(), locked -> {
            chargeOrderUnderLock(locked);
            cabinetOrderRepository.updateById(locked);
            syncPaymentFields(order, locked);
        });
    }

    private void chargeOrderUnderLock(CabinetOrder order) {
        String idemKey = "CHARGE:" + order.getOrderId() + ":" + order.getTotalAmountCents();
        if (restoreCompletedCharge(order, idemKey)) {
            return;
        }
        UserInfo user = userInfoRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        ShoppingSession session = resolveSession(order.getSessionId());
        String entryChannel = session != null ? session.getEntryChannel() : null;
        if (tryPayScoreCharge(order, user, session, entryChannel, idemKey)) {
            return;
        }
        applyBalanceCharge(order, session, idemKey);
        order.setPayChannel(PayChannels.BALANCE);
        ensurePaymentOperationId(order);
    }

    private boolean restoreCompletedCharge(CabinetOrder order, String idemKey) {
        if (!isCompleted(idemKey)) {
            return false;
        }
        order.setPayChannel(paymentOperationRepository.findByIdempotencyKey(idemKey)
                .map(PaymentOperation::getChannel).orElse(PayChannels.BALANCE));
        ensurePayTradeNo(order);
        ensurePaymentOperationId(order);
        return true;
    }

    private ShoppingSession resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionRepository.findById(sessionId).orElse(null);
    }

    private boolean tryPayScoreCharge(CabinetOrder order, UserInfo user, ShoppingSession session,
                                      String entryChannel, String idemKey) {
        if (checkoutProperties.balanceOnly()) {
            return false;
        }
        PayScoreService.ChargeResult charge = payScoreService.charge(
                user, order.getOrderId(), order.getTotalAmountCents(), "AI开门柜购物", entryChannel);
        if (PayChannels.BALANCE.equals(charge.channel())) {
            return false;
        }
        order.setPayChannel(charge.channel());
        order.setPayTradeNo(charge.tradeNo());
        recordOperation(order, CHARGE, order.getTotalAmountCents(), charge.channel(), idemKey,
                charge.tradeNo(), "order charge");
        if (session != null) {
            consumerPreauthService.releaseIfFrozen(session);
        }
        log.info("order charged channel={} order={} tradeNo={} entry={}",
                charge.channel(), order.getOrderId(), charge.tradeNo(), entryChannel);
        return true;
    }

    private void applyBalanceCharge(CabinetOrder order, ShoppingSession session, String idemKey) {
        int remainDebit = order.getTotalAmountCents();
        int capturedViaPreauth = 0;
        if (session != null) {
            int orderAmount = order.getTotalAmountCents();
            remainDebit = consumerPreauthService.captureForCharge(session, orderAmount);
            capturedViaPreauth = Math.max(0, orderAmount - remainDebit);
        }
        if (capturedViaPreauth > 0 && remainDebit > 0) {
            String preauthChargeKey = "CHARGE:PREAUTH:" + order.getOrderId() + ":" + order.getTotalAmountCents();
            if (!isCompleted(preauthChargeKey)) {
                recordOperation(order, CHARGE, capturedViaPreauth, PayChannels.BALANCE, preauthChargeKey,
                        null, "order charge via preauth capture");
            }
        }
        if (remainDebit > 0) {
            var operation = balanceLedgerService.change(order.getUserId(), -remainDebit, CHARGE,
                    order.getOrderId(), idemKey, "order charge");
            order.setPaymentOperationId(operation.getOperationId());
            order.setBalanceBeforeCents(operation.getBalanceBeforeCents());
            order.setBalanceAfterCents(operation.getBalanceAfterCents());
            return;
        }
        if (!isCompleted(idemKey)) {
            recordOperation(order, CHARGE, order.getTotalAmountCents(), PayChannels.BALANCE, idemKey,
                    null, "order charge via preauth");
        }
    }

    /** 订单已完成支付流水的净入账（分），供争议免单等场景使用。 */
    public int netCompletedCents(String orderId) {
        return paymentOperationRepository.netCompletedCents(orderId);
    }

    private void releaseSessionPreauth(CabinetOrder order) {
        if (order.getSessionId() == null || order.getSessionId().isBlank()) {
            return;
        }
        sessionRepository.findById(order.getSessionId()).ifPresent(consumerPreauthService::releaseIfFrozen);
    }

    /**
     * 争议改单差额：无外层长事务包裹渠道。
     * 短事务准备 → 真实 PayScore/退款 HTTP（事务外）→ 短事务落库；余额/mock 一次短事务完成。
     */
    public void applyPaymentDelta(CabinetOrder order, int deltaCents) {
        if (deltaCents == 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        if (deltaCents < 0) {
            refundOrder(order, -deltaCents, "争议改单退差");
            return;
        }
        if (!distributedLockService.tryLock(orderPaymentLockKey(order.getOrderId()), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单支付处理中，请稍后重试");
        }
        try {
            AdjustChargePrep prep = self.prepareAdjustCharge(order.getOrderId(), deltaCents);
            if (prep == null || prep.skipped()) {
                refreshCallerOrder(order);
                return;
            }
            if (prep.needsLiveChannel()) {
                PayScoreService.ChargeResult charge = executeLiveAdjustCharge(prep);
                self.finalizeAdjustCharge(prep, charge);
            }
            refreshCallerOrder(order);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(orderPaymentLockKey(order.getOrderId()));
        }
    }

    /**
     * 订单退款：无外层长事务包裹渠道。
     * 短事务准备 → 微信/支付宝 HTTP（事务外）→ 短事务落库；余额/mock 一次短事务完成。
     */
    public void refundOrder(CabinetOrder order, int amountCents, String reason) {
        if (amountCents <= 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        if (!distributedLockService.tryLock(orderPaymentLockKey(order.getOrderId()), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单支付处理中，请稍后重试");
        }
        try {
            OrderRefundPrep prep = self.prepareOrderRefund(order.getOrderId(), amountCents, reason);
            if (prep == null || prep.skipped()) {
                refreshCallerOrder(order);
                return;
            }
            if (prep.needsLiveChannel()) {
                executeLiveChannelRefund(prep);
                self.finalizeLiveOrderRefund(prep);
            }
            refreshCallerOrder(order);
            clawbackPointsQuietly(prep.userId(), prep.refundCents(), prep.orderId());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(orderPaymentLockKey(order.getOrderId()));
        }
    }

    public record OrderRefundPrep(
            String orderId,
            long userId,
            int refundCents,
            String reason,
            String idemKey,
            String channel,
            String payTradeNo,
            int originalChargeTotalCents,
            boolean skipped,
            boolean needsLiveChannel) {
    }

    public record AdjustChargePrep(
            String orderId,
            long userId,
            int deltaCents,
            String idemKey,
            String preferredChannel,
            boolean skipped,
            boolean needsLiveChannel) {
    }

    /**
     * 短事务：校验并完成余额/mock 退款；真实渠道仅准备参数（不调 HTTP）。
     */
    @Transactional
    public OrderRefundPrep prepareOrderRefund(String orderId, int amountCents, String reason) {
        CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        int netCharged = paymentOperationRepository.netCompletedCents(locked.getOrderId());
        if (netCharged <= 0) {
            log.warn("skip refund without prior charge order={} requested={}", locked.getOrderId(), amountCents);
            return new OrderRefundPrep(orderId, locked.getUserId(), 0, reason, null, null, null, 0, true, false);
        }
        int refundCents = Math.min(amountCents, netCharged);
        String idemKey = "REFUND:" + locked.getOrderId() + ":" + refundCents + ":" + reasonKey(reason);
        if (isCompleted(idemKey)) {
            return new OrderRefundPrep(orderId, locked.getUserId(), refundCents, reason, idemKey,
                    locked.getPayChannel(), locked.getPayTradeNo(), 0, true, false);
        }
        String channel = locked.getPayChannel() != null ? locked.getPayChannel() : PayChannels.BALANCE;
        if (PayChannels.WECHAT.equalsIgnoreCase(channel) && weChatPayProperties.isConfigured()) {
            ensurePayTradeNo(locked);
            if (locked.getPayTradeNo() == null || locked.getPayTradeNo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "缺少微信支付交易号，无法原路退款");
            }
            int totalCents = resolveOriginalChargeTotalCents(locked, refundCents);
            return new OrderRefundPrep(orderId, locked.getUserId(), refundCents, reason, idemKey,
                    PayChannels.WECHAT, locked.getPayTradeNo(), totalCents, false, true);
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(channel) && alipayPayClient.isConfigured()) {
            ensurePayTradeNo(locked);
            if (locked.getPayTradeNo() == null || locked.getPayTradeNo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "缺少支付宝交易号，无法原路退款");
            }
            return new OrderRefundPrep(orderId, locked.getUserId(), refundCents, reason, idemKey,
                    PayChannels.ALIPAY, locked.getPayTradeNo(), 0, false, true);
        }
        completeLocalRefund(locked, refundCents, reason, idemKey, channel);
        return new OrderRefundPrep(orderId, locked.getUserId(), refundCents, reason, idemKey,
                channel, locked.getPayTradeNo(), 0, false, false);
    }

    @Transactional
    public void finalizeLiveOrderRefund(OrderRefundPrep prep) {
        CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(prep.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (isCompleted(prep.idemKey())) {
            return;
        }
        String outRefundNo = deterministicRefundNo(prep.idemKey());
        recordOperation(locked, REFUND, prep.refundCents(), prep.channel(), prep.idemKey(),
                outRefundNo, prep.reason());
        locked.setRefundedAt(Instant.now());
        locked.setRefundedCents(Math.max(0, locked.getRefundedCents()) + prep.refundCents());
        cabinetOrderRepository.updateById(locked);
        log.info("live channel order refund finalized order={} channel={} amount={}",
                prep.orderId(), prep.channel(), prep.refundCents());
    }

    @Transactional
    public AdjustChargePrep prepareAdjustCharge(String orderId, int deltaCents) {
        CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        String idemKey = "ADJUST_CHARGE:" + locked.getOrderId() + ":" + deltaCents;
        if (isCompleted(idemKey)) {
            return new AdjustChargePrep(orderId, locked.getUserId(), deltaCents, idemKey,
                    locked.getPayChannel(), true, false);
        }
        String channel = locked.getPayChannel() != null ? locked.getPayChannel() : PayChannels.BALANCE;
        boolean preferLive = !checkoutProperties.balanceOnly()
                && (PayChannels.WECHAT.equalsIgnoreCase(channel) || PayChannels.ALIPAY.equalsIgnoreCase(channel));
        if (preferLive) {
            return new AdjustChargePrep(orderId, locked.getUserId(), deltaCents, idemKey, channel, false, true);
        }
        balanceLedgerService.change(locked.getUserId(), -deltaCents, ADJUST_CHARGE,
                locked.getOrderId(), idemKey, "dispute adjust charge");
        return new AdjustChargePrep(orderId, locked.getUserId(), deltaCents, idemKey, channel, false, false);
    }

    @Transactional
    public void finalizeAdjustCharge(AdjustChargePrep prep, PayScoreService.ChargeResult charge) {
        CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(prep.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (isCompleted(prep.idemKey())) {
            return;
        }
        if (charge != null && !PayChannels.BALANCE.equals(charge.channel())) {
            recordOperation(locked, ADJUST_CHARGE, prep.deltaCents(), charge.channel(), prep.idemKey(),
                    charge.tradeNo(), "dispute adjust charge");
            log.info("order adjust charge channel={} order={} delta={}",
                    charge.channel(), prep.orderId(), prep.deltaCents());
            return;
        }
        balanceLedgerService.change(locked.getUserId(), -prep.deltaCents(), ADJUST_CHARGE,
                locked.getOrderId(), prep.idemKey(), "dispute adjust charge");
    }

    private void completeLocalRefund(CabinetOrder order, int amountCents, String reason,
                                     String idemKey, String channel) {
        if (PayChannels.WECHAT.equalsIgnoreCase(channel)) {
            if (!securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
            }
            balanceLedgerService.change(order.getUserId(), amountCents, REFUND,
                    order.getOrderId(), idemKey, reasonOrDefault(reason) + LITERAL);
            recordOperation(order, REFUND, amountCents, PayChannels.WECHAT, idemKey, null,
                    reasonOrDefault(reason) + LITERAL);
            log.info("wechat mock order refund order={} amount={} credited to wallet", order.getOrderId(), amountCents);
        } else if (PayChannels.ALIPAY.equalsIgnoreCase(channel)) {
            if (!securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.ALIPAY_PAY_NOT_CONFIGURED);
            }
            balanceLedgerService.change(order.getUserId(), amountCents, REFUND,
                    order.getOrderId(), idemKey, reasonOrDefault(reason) + LITERAL);
            recordOperation(order, REFUND, amountCents, PayChannels.ALIPAY, idemKey, null,
                    reasonOrDefault(reason) + LITERAL);
            log.info("alipay mock order refund order={} amount={} credited to wallet", order.getOrderId(), amountCents);
        } else {
            balanceLedgerService.change(order.getUserId(), amountCents, REFUND,
                    order.getOrderId(), idemKey, reason);
        }
        order.setRefundedAt(Instant.now());
        order.setRefundedCents(Math.max(0, order.getRefundedCents()) + amountCents);
        cabinetOrderRepository.updateById(order);
    }

    private void executeLiveChannelRefund(OrderRefundPrep prep) {
        String outRefundNo = deterministicRefundNo(prep.idemKey());
        String reason = reasonOrDefault(prep.reason());
        if (PayChannels.WECHAT.equalsIgnoreCase(prep.channel())) {
            weChatPayClient.createRefund(prep.orderId(), outRefundNo, prep.refundCents(),
                    prep.originalChargeTotalCents(), reason);
            log.info("wechat order refund order={} amount={} total={} (原路退回零钱)",
                    prep.orderId(), prep.refundCents(), prep.originalChargeTotalCents());
            return;
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(prep.channel())) {
            alipayPayClient.refund(prep.orderId(), outRefundNo, prep.refundCents(), reason);
            log.info("alipay order refund order={} amount={}", prep.orderId(), prep.refundCents());
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "不支持的退款渠道");
    }

    private PayScoreService.ChargeResult executeLiveAdjustCharge(AdjustChargePrep prep) {
        UserInfo user = userInfoRepository.findById(prep.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        return payScoreService.charge(user, prep.orderId() + "-ADJ", prep.deltaCents(), reasonOrDefault(null));
    }

    private void refreshCallerOrder(CabinetOrder order) {
        cabinetOrderRepository.findById(order.getOrderId()).ifPresent(latest -> syncPaymentFields(order, latest));
    }

    private void clawbackPointsQuietly(long userId, int refundCents, String orderId) {
        if (refundCents <= 0 || memberService == null) {
            return;
        }
        try {
            memberService.clawbackPointsOnRefund(userId, refundCents, orderId,
                    "REFUND:" + orderId + ":" + refundCents);
        } catch (Exception e) {
            log.warn("points clawback failed order={} amount={}", orderId, refundCents, e);
        }
    }

    private static String reasonOrDefault(String reason) {
        return reason != null && !reason.isBlank() ? reason : "AI开门柜退款";
    }

    private boolean isCompleted(String idempotencyKey) {
        return paymentOperationRepository.findByIdempotencyKey(idempotencyKey)
                .map(op -> STATUS_COMPLETED.equals(op.getStatus()))
                .orElse(false);
    }

    private void recordOperation(CabinetOrder order, String type, int amountCents, String channel,
                                 String idempotencyKey, String gatewayTradeNo, String reason) {
        if (paymentOperationRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }
        PaymentOperation op = new PaymentOperation();
        op.setOperationId(resolveOperationId(type, channel));
        op.setOrderId(order.getOrderId());
        op.setOperationType(type);
        op.setAmountCents(amountCents);
        op.setChannel(channel);
        op.setStatus(STATUS_COMPLETED);
        op.setIdempotencyKey(idempotencyKey);
        op.setGatewayTradeNo(gatewayTradeNo);
        op.setReason(reason != null && reason.length() > 128 ? reason.substring(0, 128) : reason);
        paymentOperationRepository.save(op);
    }

    private static String deterministicRefundNo(String idempotencyKey) {
        String suffix = Integer.toUnsignedString(idempotencyKey.hashCode(), 36).toUpperCase();
        return ("RF" + suffix + "00000000000000").substring(0, 16);
    }

    private static String reasonKey(String reason) {
        if (reason == null || reason.isBlank()) {
            return "DEFAULT";
        }
        return Integer.toUnsignedString(reason.hashCode(), 36).toUpperCase();
    }

    private static String resolveOperationId(String type, String channel) {
        if (PayChannels.BALANCE.equalsIgnoreCase(channel)) {
            return BizIds.nextNumeric();
        }
        return type + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
    }

    /**
     * Legacy 订单可能未写入 payment_operation_id，但 CHARGE 流水已落库。
     */
    private void ensurePaymentOperationId(CabinetOrder order) {
        if (order.getPaymentOperationId() != null && !order.getPaymentOperationId().isBlank()) {
            return;
        }
        paymentOperationRepository.findLatestChargeOperationId(order.getOrderId())
                .ifPresent(opId -> {
                    int updated = cabinetOrderRepository.backfillPaymentOperationIdIfAbsent(order.getOrderId(), opId);
                    if (updated > 0) {
                        order.setPaymentOperationId(opId);
                        log.info("backfilled paymentOperationId order={} opId={}", order.getOrderId(), opId);
                    } else {
                        cabinetOrderRepository.findById(order.getOrderId())
                                .map(CabinetOrder::getPaymentOperationId)
                                .filter(s -> s != null && !s.isBlank())
                                .ifPresent(order::setPaymentOperationId);
                    }
                });
    }

    /**
     * Legacy 订单可能未写入 pay_trade_no，但 CHARGE 流水已记录 gateway_trade_no。
     * 退款前回填并持久化，避免 OPS-02 误拒。
     */
    private void ensurePayTradeNo(CabinetOrder order) {
        if (order.getPayTradeNo() != null && !order.getPayTradeNo().isBlank()) {
            return;
        }
        String channel = order.getPayChannel();
        if (channel == null || channel.isBlank()) {
            return;
        }
        paymentOperationRepository.findLatestGatewayTradeNoForCharge(order.getOrderId(), channel)
                .ifPresent(tradeNo -> {
                    int updated = cabinetOrderRepository.backfillPayTradeNoIfAbsent(order.getOrderId(), tradeNo);
                    if (updated > 0) {
                        order.setPayTradeNo(tradeNo);
                        log.info("backfilled payTradeNo order={} channel={} tradeNo={}",
                                order.getOrderId(), channel, tradeNo);
                    } else {
                        cabinetOrderRepository.findById(order.getOrderId())
                                .map(CabinetOrder::getPayTradeNo)
                                .filter(s -> s != null && !s.isBlank())
                                .ifPresent(order::setPayTradeNo);
                    }
                });
    }

    static String orderPaymentLockKey(String orderId) {
        return "order:payment:" + orderId;
    }

    @FunctionalInterface
    private interface LockedOrderConsumer {
        void accept(CabinetOrder locked);
    }

    private void runWithOrderPaymentLock(String orderId, LockedOrderConsumer action) {
        if (!distributedLockService.tryLock(orderPaymentLockKey(orderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单支付处理中，请稍后重试");
        }
        try {
            CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
            action.accept(locked);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(orderPaymentLockKey(orderId));
        }
    }

    private static void syncPaymentFields(CabinetOrder target, CabinetOrder locked) {
        target.setPayChannel(locked.getPayChannel());
        target.setPayTradeNo(locked.getPayTradeNo());
        target.setPaymentOperationId(locked.getPaymentOperationId());
        target.setBalanceBeforeCents(locked.getBalanceBeforeCents());
        target.setBalanceAfterCents(locked.getBalanceAfterCents());
        target.setRefundedCents(locked.getRefundedCents());
        target.setRefundedAt(locked.getRefundedAt());
    }

    /**
     * 微信退款接口要求 total=原支付金额。取本单 COMPLETED CHARGE 合计；
     * 无流水时回退到 max(当前订单额, 本次退款额)。
     */
    private int resolveOriginalChargeTotalCents(CabinetOrder order, int refundCents) {
        int charged = paymentOperationRepository.selectList(
                        Wrappers.<PaymentOperation>lambdaQuery()
                                .eq(PaymentOperation::getOrderId, order.getOrderId())
                                .eq(PaymentOperation::getStatus, STATUS_COMPLETED)
                                .eq(PaymentOperation::getOperationType, CHARGE))
                .stream()
                .mapToInt(PaymentOperation::getAmountCents)
                .sum();
        if (charged <= 0) {
            charged = Math.max(order.getTotalAmountCents(), refundCents);
        }
        return Math.max(charged, refundCents);
    }

    /** 支付操作创建时间，用作订单 paidAt。 */
    @Transactional(readOnly = true)
    public java.util.Optional<Instant> findOperationCreatedAt(String paymentOperationId) {
        if (paymentOperationId == null || paymentOperationId.isBlank()) {
            return java.util.Optional.empty();
        }
        return paymentOperationRepository.findById(paymentOperationId.trim())
                .map(PaymentOperation::getCreatedAt)
                .filter(at -> at != null);
    }
}
