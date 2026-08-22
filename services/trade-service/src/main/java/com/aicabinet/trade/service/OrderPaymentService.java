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
import com.aicabinet.trade.support.ApiMessages;
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
                               @Lazy MemberService memberService) {
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
    }

    @Transactional
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
            syncPaymentFields(order, locked);
        });
    }

    private void chargeOrderUnderLock(CabinetOrder order) {
        String idemKey = "CHARGE:" + order.getOrderId() + ":" + order.getTotalAmountCents();
        if (isCompleted(idemKey)) {
            order.setPayChannel(paymentOperationRepository.findByIdempotencyKey(idemKey)
                    .map(PaymentOperation::getChannel).orElse(PayChannels.BALANCE));
            ensurePayTradeNo(order);
            return;
        }
        UserInfo user = userInfoRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));

        ShoppingSession session = null;
        String entryChannel = null;
        if (order.getSessionId() != null && !order.getSessionId().isBlank()) {
            session = sessionRepository.findById(order.getSessionId()).orElse(null);
            if (session != null) {
                entryChannel = session.getEntryChannel();
            }
        }

        if (!checkoutProperties.balanceOnly()) {
            PayScoreService.ChargeResult charge = payScoreService.charge(
                    user, order.getOrderId(), order.getTotalAmountCents(), "AI开门柜购物", entryChannel);
            if (!PayChannels.BALANCE.equals(charge.channel())) {
                order.setPayChannel(charge.channel());
                order.setPayTradeNo(charge.tradeNo());
                recordOperation(order, "CHARGE", order.getTotalAmountCents(), charge.channel(), idemKey,
                        charge.tradeNo(), "order charge");
                if (session != null) {
                    consumerPreauthService.releaseIfFrozen(session);
                }
                log.info("order charged channel={} order={} tradeNo={} entry={}",
                        charge.channel(), order.getOrderId(), charge.tradeNo(), entryChannel);
                return;
            }
        }

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
                recordOperation(order, "CHARGE", capturedViaPreauth, PayChannels.BALANCE, preauthChargeKey,
                        null, "order charge via preauth capture");
            }
        }
        if (remainDebit > 0) {
            var operation = balanceLedgerService.change(order.getUserId(), -remainDebit, "CHARGE",
                    order.getOrderId(), idemKey, "order charge");
            order.setPaymentOperationId(operation.getOperationId());
            order.setBalanceBeforeCents(operation.getBalanceBeforeCents());
            order.setBalanceAfterCents(operation.getBalanceAfterCents());
        } else {
            // 全额由预授权冲抵：仍记一条零侧审计用的 CHARGE 幂等键，防止重复扣
            if (!isCompleted(idemKey)) {
                recordOperation(order, "CHARGE", order.getTotalAmountCents(), PayChannels.BALANCE, idemKey,
                        null, "order charge via preauth");
            }
        }
        order.setPayChannel(PayChannels.BALANCE);
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

    @Transactional
    public void applyPaymentDelta(CabinetOrder order, int deltaCents) {
        if (deltaCents == 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        runWithOrderPaymentLock(order.getOrderId(), locked -> {
            if (deltaCents > 0) {
                chargeDelta(locked, deltaCents);
            } else {
                refundAmount(locked, -deltaCents, "争议改单退差");
                locked.setRefundedCents(Math.max(0, locked.getRefundedCents()) + (-deltaCents));
                locked.setRefundedAt(Instant.now());
                cabinetOrderRepository.updateById(locked);
            }
            syncPaymentFields(order, locked);
        });
    }

    @Transactional
    public void refundOrder(CabinetOrder order, int amountCents, String reason) {
        if (amountCents <= 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        runWithOrderPaymentLock(order.getOrderId(), locked -> {
            int netCharged = paymentOperationRepository.netCompletedCents(locked.getOrderId());
            if (netCharged <= 0) {
                log.warn("skip refund without prior charge order={} requested={}", locked.getOrderId(), amountCents);
                return;
            }
            int refundCents = Math.min(amountCents, netCharged);
            refundAmount(locked, refundCents, reason);
            try {
                memberService.clawbackPointsOnRefund(locked.getUserId(), refundCents,
                        locked.getOrderId(), "REFUND:" + locked.getOrderId() + ":" + refundCents);
            } catch (Exception e) {
                log.warn("points clawback failed order={} amount={}", locked.getOrderId(), refundCents, e);
            }
            locked.setRefundedAt(Instant.now());
            locked.setRefundedCents(Math.max(0, locked.getRefundedCents()) + refundCents);
            cabinetOrderRepository.updateById(locked);
            syncPaymentFields(order, locked);
        });
    }

    private void chargeDelta(CabinetOrder order, int deltaCents) {
        String idemKey = "ADJUST_CHARGE:" + order.getOrderId() + ":" + deltaCents;
        if (isCompleted(idemKey)) {
            return;
        }
        String channel = order.getPayChannel() != null ? order.getPayChannel() : PayChannels.BALANCE;
        if (PayChannels.WECHAT.equalsIgnoreCase(channel) || PayChannels.ALIPAY.equalsIgnoreCase(channel)) {
            UserInfo user = userInfoRepository.findById(order.getUserId()).orElseThrow();
            PayScoreService.ChargeResult charge = payScoreService.charge(user, order.getOrderId() + "-ADJ", deltaCents, reasonOrDefault(null));
            if (!PayChannels.BALANCE.equals(charge.channel())) {
                recordOperation(order, "ADJUST_CHARGE", deltaCents, charge.channel(), idemKey,
                        charge.tradeNo(), "dispute adjust charge");
                log.info("order adjust charge channel={} order={} delta={}", charge.channel(), order.getOrderId(), deltaCents);
                return;
            }
        }
        balanceLedgerService.change(order.getUserId(), -deltaCents, "ADJUST_CHARGE",
                order.getOrderId(), idemKey, "dispute adjust charge");
    }

    private void refundAmount(CabinetOrder order, int amountCents, String reason) {
        String idemKey = "REFUND:" + order.getOrderId() + ":" + amountCents + ":" + reasonKey(reason);
        if (isCompleted(idemKey)) {
            return;
        }
        String channel = order.getPayChannel() != null ? order.getPayChannel() : PayChannels.BALANCE;
        if (PayChannels.WECHAT.equalsIgnoreCase(channel)) {
            refundWeChat(order, amountCents, reason, idemKey);
            return;
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(channel)) {
            refundAlipay(order, amountCents, reason, idemKey);
            return;
        }
        balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                order.getOrderId(), idemKey, reason);
    }

    private void refundWeChat(CabinetOrder order, int amountCents, String reason, String idemKey) {
        if (weChatPayProperties.isConfigured()) {
            ensurePayTradeNo(order);
            if (order.getPayTradeNo() == null || order.getPayTradeNo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "缺少微信支付交易号，无法原路退款");
            }
            String outRefundNo = deterministicRefundNo(idemKey);
            // 微信退款 total 必须是原支付单金额；改单后 order.total 可能已变，不能直接用
            int totalCents = resolveOriginalChargeTotalCents(order, amountCents);
            weChatPayClient.createRefund(order.getOrderId(), outRefundNo, amountCents, totalCents, reasonOrDefault(reason));
            recordOperation(order, "REFUND", amountCents, PayChannels.WECHAT, idemKey, outRefundNo, reason);
            log.info("wechat order refund order={} amount={} total={} (原路退回零钱)",
                    order.getOrderId(), amountCents, totalCents);
            return;
        }
        if (securityProperties.mockEnabled()) {
            // Mock 支付分未真实扣款时，退款记入余额（balanceLedgerService 已写 payment_operation）
            balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                    order.getOrderId(), idemKey, reasonOrDefault(reason) + "（模拟支付退回余额）");
            recordOperation(order, "REFUND", amountCents, PayChannels.WECHAT, idemKey, null,
                    reasonOrDefault(reason) + "（模拟支付退回余额）");
            log.info("wechat mock order refund order={} amount={} credited to wallet", order.getOrderId(), amountCents);
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
    }

    private void refundAlipay(CabinetOrder order, int amountCents, String reason, String idemKey) {
        if (alipayPayClient.isConfigured()) {
            ensurePayTradeNo(order);
            if (order.getPayTradeNo() == null || order.getPayTradeNo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "缺少支付宝交易号，无法原路退款");
            }
            String outRefundNo = deterministicRefundNo(idemKey);
            alipayPayClient.refund(order.getOrderId(), outRefundNo, amountCents, reasonOrDefault(reason));
            recordOperation(order, "REFUND", amountCents, PayChannels.ALIPAY, idemKey, outRefundNo, reason);
            log.info("alipay order refund order={} amount={}", order.getOrderId(), amountCents);
            return;
        }
        if (securityProperties.mockEnabled()) {
            balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                    order.getOrderId(), idemKey, reasonOrDefault(reason) + "（模拟支付退回余额）");
            recordOperation(order, "REFUND", amountCents, PayChannels.ALIPAY, idemKey, null,
                    reasonOrDefault(reason) + "（模拟支付退回余额）");
            log.info("alipay mock order refund order={} amount={} credited to wallet", order.getOrderId(), amountCents);
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.ALIPAY_PAY_NOT_CONFIGURED);
    }

    private static String reasonOrDefault(String reason) {
        return reason != null && !reason.isBlank() ? reason : "AI开门柜退款";
    }

    private boolean isCompleted(String idempotencyKey) {
        return paymentOperationRepository.findByIdempotencyKey(idempotencyKey)
                .map(op -> "COMPLETED".equals(op.getStatus()))
                .orElse(false);
    }

    private void recordOperation(CabinetOrder order, String type, int amountCents, String channel,
                                 String idempotencyKey, String gatewayTradeNo, String reason) {
        if (paymentOperationRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }
        PaymentOperation op = new PaymentOperation();
        op.setOperationId(type + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase());
        op.setOrderId(order.getOrderId());
        op.setOperationType(type);
        op.setAmountCents(amountCents);
        op.setChannel(channel);
        op.setStatus("COMPLETED");
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
        void accept(CabinetOrder locked) throws Exception;
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
                                .eq(PaymentOperation::getStatus, "COMPLETED")
                                .eq(PaymentOperation::getOperationType, "CHARGE"))
                .stream()
                .mapToInt(PaymentOperation::getAmountCents)
                .sum();
        if (charged <= 0) {
            charged = Math.max(order.getTotalAmountCents(), refundCents);
        }
        return Math.max(charged, refundCents);
    }
}
