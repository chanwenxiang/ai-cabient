package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final UserAccountMapper userAccountRepository;
    private final BalanceLedgerService balanceLedgerService;
    private final CheckoutProperties checkoutProperties;
    private final PayScoreService payScoreService;
    private final WeChatPayClient weChatPayClient;
    private final AlipayPayClient alipayPayClient;
    private final WeChatPayProperties weChatPayProperties;
    private final SecurityProperties securityProperties;
    private final PaymentOperationMapper paymentOperationRepository;
    private final ShoppingSessionMapper sessionRepository;

    public OrderPaymentService(UserInfoMapper userInfoRepository,
                               UserAccountMapper userAccountRepository,
                               PayScoreService payScoreService,
                               WeChatPayClient weChatPayClient,
                               AlipayPayClient alipayPayClient,
                               WeChatPayProperties weChatPayProperties,
                               SecurityProperties securityProperties,
                               PaymentOperationMapper paymentOperationRepository,
                               BalanceLedgerService balanceLedgerService,
                               CheckoutProperties checkoutProperties,
                               ShoppingSessionMapper sessionRepository) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.payScoreService = payScoreService;
        this.weChatPayClient = weChatPayClient;
        this.alipayPayClient = alipayPayClient;
        this.weChatPayProperties = weChatPayProperties;
        this.securityProperties = securityProperties;
        this.paymentOperationRepository = paymentOperationRepository;
        this.balanceLedgerService = balanceLedgerService;
        this.checkoutProperties = checkoutProperties;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void chargeOrder(CabinetOrder order) {
        if (order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            order.setPayChannel(PayChannels.BALANCE);
            return;
        }
        // 零元单（未取货 / 券全额抵扣）不走账本，避免 delta=0 被拒
        if (order.getTotalAmountCents() <= 0) {
            order.setPayChannel(PayChannels.BALANCE);
            return;
        }
        String idemKey = "CHARGE:" + order.getOrderId() + ":" + order.getTotalAmountCents();
        if (isCompleted(idemKey)) {
            order.setPayChannel(paymentOperationRepository.findByIdempotencyKey(idemKey)
                    .map(PaymentOperation::getChannel).orElse(PayChannels.BALANCE));
            return;
        }
        UserInfo user = userInfoRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));

        String entryChannel = null;
        if (order.getSessionId() != null && !order.getSessionId().isBlank()) {
            entryChannel = sessionRepository.findById(order.getSessionId())
                    .map(ShoppingSession::getEntryChannel)
                    .orElse(null);
        }

        if (!checkoutProperties.balanceOnly()) {
            PayScoreService.ChargeResult charge = payScoreService.charge(
                    user, order.getOrderId(), order.getTotalAmountCents(), "AI开门柜购物", entryChannel);
            if (!PayChannels.BALANCE.equals(charge.channel())) {
                order.setPayChannel(charge.channel());
                order.setPayTradeNo(charge.tradeNo());
                recordOperation(order, "CHARGE", order.getTotalAmountCents(), charge.channel(), idemKey,
                        charge.tradeNo(), "order charge");
                log.info("order charged channel={} order={} tradeNo={} entry={}",
                        charge.channel(), order.getOrderId(), charge.tradeNo(), entryChannel);
                return;
            }
        }

        var operation = balanceLedgerService.change(order.getUserId(), -order.getTotalAmountCents(), "CHARGE",
                order.getOrderId(), idemKey, "order charge");
        order.setPayChannel(PayChannels.BALANCE);
        order.setPaymentOperationId(operation.getOperationId());
        order.setBalanceBeforeCents(operation.getBalanceBeforeCents());
        order.setBalanceAfterCents(operation.getBalanceAfterCents());
    }

    @Transactional
    public void applyPaymentDelta(CabinetOrder order, int deltaCents) {
        if (deltaCents == 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        if (deltaCents > 0) {
            chargeDelta(order, deltaCents);
        } else {
            refundAmount(order, -deltaCents, "争议改单退差");
        }
    }

    @Transactional
    public void refundOrder(CabinetOrder order, int amountCents, String reason) {
        if (amountCents <= 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        refundAmount(order, amountCents, reason);
        order.setRefundedAt(Instant.now());
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
        if (weChatPayProperties.isConfigured() && order.getPayTradeNo() != null) {
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
            recordOperation(order, "REFUND", amountCents, PayChannels.WECHAT, idemKey, null, reason);
            // Mock 支付分未真实扣款时，退款记入余额，保证争议免单/退差在演示链路可见。
            balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                    order.getOrderId(), idemKey + ":wallet", reasonOrDefault(reason) + "（模拟支付退回余额）");
            log.info("wechat mock order refund order={} amount={} credited to wallet", order.getOrderId(), amountCents);
            return;
        }
        balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                order.getOrderId(), idemKey, reason);
    }

    private void refundAlipay(CabinetOrder order, int amountCents, String reason, String idemKey) {
        if (alipayPayClient.isConfigured()) {
            String outRefundNo = deterministicRefundNo(idemKey);
            alipayPayClient.refund(order.getOrderId(), outRefundNo, amountCents, reasonOrDefault(reason));
            recordOperation(order, "REFUND", amountCents, PayChannels.ALIPAY, idemKey, outRefundNo, reason);
            log.info("alipay order refund order={} amount={}", order.getOrderId(), amountCents);
            return;
        }
        if (securityProperties.mockEnabled()) {
            recordOperation(order, "REFUND", amountCents, PayChannels.ALIPAY, idemKey, null, reason);
            balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                    order.getOrderId(), idemKey + ":wallet", reasonOrDefault(reason) + "（模拟支付退回余额）");
            log.info("alipay mock order refund order={} amount={} credited to wallet", order.getOrderId(), amountCents);
            return;
        }
        balanceLedgerService.change(order.getUserId(), amountCents, "REFUND",
                order.getOrderId(), idemKey, reason);
    }

    private void deductBalance(Long userId, int amountCents) {
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        account.setBalanceCents(account.getBalanceCents() - amountCents);
        userAccountRepository.save(account);
    }

    private void creditBalance(Long userId, int amountCents) {
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        account.setBalanceCents(account.getBalanceCents() + amountCents);
        userAccountRepository.save(account);
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
