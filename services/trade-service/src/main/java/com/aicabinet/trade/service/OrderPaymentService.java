package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.InvoiceRequest;
import com.aicabinet.trade.domain.PayScoreOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AgreementChargeClient;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.InvoiceRequestMapper;
import com.aicabinet.trade.mapper.PayScoreOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.util.BizIds;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class OrderPaymentService {
    private static final String LITERAL = "（模拟支付退回余额）";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String REFUND = "REFUND";
    private static final String CHARGE = "CHARGE";
    private static final String ADJUST_CHARGE = "ADJUST_CHARGE";
    /** 渠道扣款描述（免密账单展示用）；自动决策与显式选择两条路径共用同一文案。 */
    private static final String CHARGE_SUBJECT = "AI开门柜购物";


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
    private final PayScoreOrderMapper payScoreOrderMapper;
    private final AgreementChargeClient agreementChargeClient;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final InvoiceRequestMapper invoiceRequestMapper;
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
                               PayScoreOrderMapper payScoreOrderMapper,
                               AgreementChargeClient agreementChargeClient,
                               OpsAlertDispatcher opsAlertDispatcher,
                               InvoiceRequestMapper invoiceRequestMapper,
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
        this.payScoreOrderMapper = payScoreOrderMapper;
        this.agreementChargeClient = agreementChargeClient;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.invoiceRequestMapper = invoiceRequestMapper;
        this.memberService = memberService;
        this.self = self;
    }

    /**
     * 订单扣款：须参与调用方事务，以便结算/争议确认中「先落单再扣款」可见未提交订单。
     * H41: 渠道 HTTP 前先以独立事务（REQUIRES_NEW）落 CHARGE_PENDING 痕迹并立即提交；
     * 渠道已扣款而外层结算事务回滚时，凭该记录补偿/人工介入，不再出现「无痕迹双扣」。
     *
     * <p>渠道由服务端自动决策（用户偏好 → 扫码入口渠道 → 已签约渠道 → 余额兜底）；
     * 自动结算与运营代收走这条。
     */
    @Transactional
    public void chargeOrder(CabinetOrder order) {
        chargeOrder(order, null);
    }

    /**
     * 带**显式渠道**的订单扣款（F6「结算页支付方式选择」）。
     *
     * <p>{@code requestedChannel} 为空白 ⇒ 完全等同于 {@link #chargeOrder(CabinetOrder)}（自动决策），
     * 即老客户端/老调用方行为不变；非空 ⇒ 只按该渠道扣款、**不降级**
     * （见 {@link #chargeWithSelectedChannel}）。
     */
    @Transactional
    public void chargeOrder(CabinetOrder order, String requestedChannel) {
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
            chargeOrderUnderLock(locked, requestedChannel);
            cabinetOrderRepository.updateById(locked);
            syncPaymentFields(order, locked);
        });
    }

    private void chargeOrderUnderLock(CabinetOrder order, String requestedChannel) {
        String idemKey = "CHARGE:" + order.getOrderId() + ":" + order.getTotalAmountCents();
        if (restoreCompletedCharge(order, idemKey)) {
            return;
        }
        UserInfo user = userInfoRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        ShoppingSession session = resolveSession(order.getSessionId());
        if (requestedChannel != null && !requestedChannel.isBlank()) {
            chargeWithSelectedChannel(order, user, session, requestedChannel, idemKey);
            return;
        }
        String entryChannel = session != null ? session.getEntryChannel() : null;
        if (tryPayScoreCharge(order, user, session, entryChannel, idemKey)) {
            return;
        }
        applyBalanceCharge(order, session, idemKey);
        order.setPayChannel(PayChannels.BALANCE);
        ensurePaymentOperationId(order);
    }

    /**
     * 只按用户**显式选择**的渠道扣款，**不降级**：渠道未就绪或在本环境不可用 ⇒ 412，
     * 由前端提示改选或先去开通。
     *
     * <p>与自动结算的差别是刻意的：自动结算允许「免密不可用 ⇒ 回落余额」，
     * 但用户显式选了免密却被扣余额，是**背离用户意图**的资金动作，必须显式失败。
     */
    private void chargeWithSelectedChannel(CabinetOrder order, UserInfo user, ShoppingSession session,
                                           String requestedChannel, String idemKey) {
        String channel = requestedChannel.trim().toUpperCase(Locale.ROOT);
        if (!PayScoreService.isChannelUsable(user, channel)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.PAY_CHANNEL_NOT_READY);
        }
        if (PayChannels.BALANCE.equals(channel)) {
            applyBalanceCharge(order, session, idemKey);
            order.setPayChannel(PayChannels.BALANCE);
            ensurePaymentOperationId(order);
            return;
        }
        if (checkoutProperties.balanceOnly()) {
            // 余额专用环境（aicabinet.checkout.balance-only）：不接受免密渠道，同样不静默切回余额
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.PAY_CHANNEL_NOT_READY);
        }
        boolean charged = submitChannelCharge(order, user, session, idemKey,
                () -> payScoreService.chargeExplicit(user, order.getOrderId(),
                        order.getTotalAmountCents(), CHARGE_SUBJECT, channel),
                "selected:" + channel);
        if (!charged) {
            // chargeExplicit 对非余额渠道「失败即抛」，返回 BALANCE 只可能源自金额非正（上面已拦）
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.PAY_CHANNEL_NOT_READY);
        }
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

    /** 自动决策路径的渠道扣款：渠道不可用（返回 BALANCE）时让调用方回落余额。 */
    private boolean tryPayScoreCharge(CabinetOrder order, UserInfo user, ShoppingSession session,
                                      String entryChannel, String idemKey) {
        if (checkoutProperties.balanceOnly()) {
            return false;
        }
        String origin = entryChannel == null || entryChannel.isBlank() ? "auto" : "auto:" + entryChannel;
        return submitChannelCharge(order, user, session, idemKey,
                () -> payScoreService.charge(user, order.getOrderId(), order.getTotalAmountCents(),
                        CHARGE_SUBJECT, entryChannel),
                origin);
    }

    /**
     * 渠道扣款的公共部分（自动决策与显式选择共用同一套痕迹/落账语义）：
     * H41 CHARGE_PENDING 痕迹 → 提交渠道 → 成功则落 CHARGE 流水 + FINALIZED + 释放预授权。
     *
     * @return {@code false} ⇒ 渠道判定为不可用（结果渠道为 BALANCE），**未扣任何款**，由调用方决定回落还是报错
     */
    private boolean submitChannelCharge(CabinetOrder order, UserInfo user, ShoppingSession session, String idemKey,
                                        Supplier<PayScoreService.ChargeResult> submit, String origin) {
        // H41: 渠道 HTTP 之前先以独立事务落 CHARGE_PENDING 并立即提交（mock 路径跳过，行为不变）
        if (!securityProperties.mockEnabled()) {
            self.recordChargePending(order);
        }
        PayScoreService.ChargeResult charge;
        try {
            charge = submit.get();
        } catch (RuntimeException e) {
            // H41: 失败保留 CHARGE_PENDING 供补偿/人工（REQUIRES_NEW 已提交，不受外层回滚影响）；
            // 不降级余额、不盲切渠道
            log.error("payscore charge failed, CHARGE_PENDING kept order={} channelHint={} origin={} err={}",
                    order.getOrderId(), order.getPayChannel(), origin, e.getMessage(), e);
            throw e;
        }
        if (PayChannels.BALANCE.equals(charge.channel())) {
            return false;
        }
        order.setPayChannel(charge.channel());
        order.setPayTradeNo(charge.tradeNo());
        recordOperation(order, CHARGE, order.getTotalAmountCents(), charge.channel(), idemKey,
                charge.tradeNo(), "order charge");
        self.finalizeChargePending(order.getOrderId(), charge.tradeNo());
        recordPayScoreOrder(order, user, charge);
        if (session != null) {
            consumerPreauthService.releaseIfFrozen(session);
        }
        log.info("order charged channel={} order={} tradeNo={} origin={}",
                charge.channel(), order.getOrderId(), charge.tradeNo(), origin);
        return true;
    }

    static String chargePendingIdempotencyKey(String orderId) {
        return "CHARGE:PENDING:" + orderId;
    }

    /** H41: 独立事务落 CHARGE_PENDING 痕迹（立即提交，外层回滚不影响）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChargePending(CabinetOrder order) {
        String pendingKey = chargePendingIdempotencyKey(order.getOrderId());
        if (paymentOperationRepository.findByIdempotencyKey(pendingKey).isPresent()) {
            return;
        }
        PaymentOperation op = new PaymentOperation();
        op.setOperationId(BizIds.nextNumeric());
        op.setOrderId(order.getOrderId());
        op.setOperationType(CHARGE);
        op.setAmountCents(order.getTotalAmountCents());
        op.setChannel(order.getPayChannel() != null && !order.getPayChannel().isBlank()
                ? order.getPayChannel() : "PENDING");
        op.setStatus("CHARGE_PENDING");
        op.setIdempotencyKey(pendingKey);
        op.setReason("渠道扣款进行中（CHARGE_PENDING，事务外痕迹）");
        paymentOperationRepository.save(op);
    }

    /** H41: 渠道成功后把 CHARGE_PENDING 标记为 FINALIZED（仅审计；不计入 netCompletedCents）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeChargePending(String orderId, String tradeNo) {
        paymentOperationRepository.findByIdempotencyKey(chargePendingIdempotencyKey(orderId))
                .filter(op -> "CHARGE_PENDING".equals(op.getStatus()))
                .ifPresent(op -> {
                    op.setStatus("FINALIZED");
                    if (tradeNo != null && !tradeNo.isBlank()) {
                        op.setGatewayTradeNo(tradeNo);
                    }
                    paymentOperationRepository.updateById(op);
                });
    }

    /** H64(a): 支付分/代扣渠道扣款成功后落 payscore_order 一行（含渠道单号与状态）。 */
    private void recordPayScoreOrder(CabinetOrder order, UserInfo user, PayScoreService.ChargeResult charge) {
        if (!PayChannels.WECHAT.equalsIgnoreCase(charge.channel()) || payScoreOrderMapper == null) {
            return;
        }
        try {
            PayScoreOrder psOrder = new PayScoreOrder();
            psOrder.setPayscoreOrderId("PSO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
            psOrder.setOrderId(order.getOrderId());
            psOrder.setUserId(order.getUserId());
            psOrder.setOpenId(user.getWxOpenId() == null ? "" : user.getWxOpenId());
            // gateway 代扣场景无微信 service_id：以签约协议号标识扣款依据
            psOrder.setServiceId(user.getPayscoreContractId() == null ? "" : user.getPayscoreContractId());
            psOrder.setOutOrderNo(order.getOrderId());
            psOrder.setServiceStartTime(Instant.now());
            psOrder.setServiceEndTime(Instant.now());
            psOrder.setTotalAmountCents(order.getTotalAmountCents());
            psOrder.setActualAmountCents(order.getTotalAmountCents());
            psOrder.setOrderState("DONE");
            psOrder.setNeedUserConfirm(false);
            psOrder.setNeedCollection(false);
            psOrder.setCreatedAt(Instant.now());
            psOrder.setUpdatedAt(Instant.now());
            payScoreOrderMapper.insert(psOrder);
        } catch (Exception e) {
            // 痕迹落库失败不阻断扣款主流程，但必须留日志
            log.error("payscore_order persist failed order={}", order.getOrderId(), e);
        }
    }

    /** H64(b): 会话取消时释放 payscore_order —— 未支付单置 CANCELLED，已支付单标记需退款并告警。 */
    public void cancelPayScoreOrdersForSession(String orderId) {
        if (payScoreOrderMapper == null || orderId == null || orderId.isBlank()) {
            return;
        }
        for (PayScoreOrder psOrder : payScoreOrderMapper.findByOrderId(orderId)) {
            String state = psOrder.getOrderState() == null ? "" : psOrder.getOrderState();
            if ("DONE".equalsIgnoreCase(state)) {
                // 真实渠道释放/退款 API 未接入（明确不在代码修复内）：先落本地退款标记与告警
                psOrder.setOrderState("REFUND_REQUIRED");
                psOrder.setUpdatedAt(Instant.now());
                payScoreOrderMapper.updateById(psOrder);
                log.error("session cancel but payscore order already charged, refund required order={} psOrder={}",
                        orderId, psOrder.getPayscoreOrderId());
                try {
                    opsAlertDispatcher.send("PAYSCORE_ORDER_REFUND_REQUIRED", "支付分订单取消需退款",
                            "会话取消但渠道已扣款，需人工退款 orderId=" + orderId
                                    + " payscoreOrder=" + psOrder.getPayscoreOrderId());
                } catch (Exception e) {
                    log.warn("ops alert failed payscore refund required orderId={}", orderId, e);
                }
            } else if (!"CANCELLED".equalsIgnoreCase(state) && !"REFUND_REQUIRED".equalsIgnoreCase(state)) {
                psOrder.setOrderState("CANCELLED");
                psOrder.setUpdatedAt(Instant.now());
                payScoreOrderMapper.updateById(psOrder);
                log.info("payscore order cancelled on session cancel order={} psOrder={}",
                        orderId, psOrder.getPayscoreOrderId());
            }
        }
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
                String refundStatus = executeLiveChannelRefund(prep);
                self.finalizeLiveOrderRefund(prep, refundStatus);
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
        // M02: 幂等键只含订单+金额（reason 可变，勿入键，否则同单同额不同文案会重复退款）
        String idemKey = "REFUND:" + locked.getOrderId() + ":" + refundCents;
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
        alertIfInvoicedOnFullRefund(locked, refundCents);
        return new OrderRefundPrep(orderId, locked.getUserId(), refundCents, reason, idemKey,
                channel, locked.getPayTradeNo(), 0, false, false);
    }

    @Transactional
    public void finalizeLiveOrderRefund(OrderRefundPrep prep) {
        finalizeLiveOrderRefund(prep, null);
    }

    @Transactional
    public void finalizeLiveOrderRefund(OrderRefundPrep prep, String channelRefundStatus) {
        CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(prep.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (isCompleted(prep.idemKey())) {
            return;
        }
        String outRefundNo = deterministicRefundNo(prep.idemKey());
        String reason = channelRefundStatus != null && !channelRefundStatus.isBlank()
                ? prep.reason() + " [refund:" + channelRefundStatus + "]"
                : prep.reason();
        recordOperation(locked, REFUND, prep.refundCents(), prep.channel(), prep.idemKey(),
                outRefundNo, reason);
        locked.setRefundedAt(Instant.now());
        locked.setRefundedCents(Math.max(0, locked.getRefundedCents()) + prep.refundCents());
        cabinetOrderRepository.updateById(locked);
        alertIfInvoicedOnFullRefund(locked, prep.refundCents());
        log.info("live channel order refund finalized order={} channel={} amount={} refundStatus={}",
                prep.orderId(), prep.channel(), prep.refundCents(), channelRefundStatus);
    }

    /**
     * H42(a): 解析微信 createRefund 响应状态（PROCESSING/SUCCESS/ABNORMAL）。
     * ABNORMAL 时记 error 并发运营告警；状态随退款流水落库（reason 标记），
     * PROCESSING 单由每日对账的 {@code WeChatRefundReconciler} 调 queryRefund 推进。
     * 注：微信退款结果回调 Controller 暂不新增（无回调地址配置入口），以查单推进兜底。
     */
    static String weChatRefundStatus(com.fasterxml.jackson.databind.JsonNode response) {
        if (response == null) {
            return "UNKNOWN";
        }
        String status = response.path("status").asText("");
        return status.isBlank() ? "UNKNOWN" : status;
    }

    /**
     * H42(b): 微信查单笔退款由对账调度（WeChatRefundReconciler）直接调
     * {@code WeChatPayV3Client#queryRefund(outTradeNo, outRefundNo)} 推进。
     */

    /** M22(b): 订单已开票且本次退款将覆盖全部实付时，发运营告警「需人工红冲」。 */
    private void alertIfInvoicedOnFullRefund(CabinetOrder order, int refundCents) {
        try {
            if (invoiceRequestMapper == null || opsAlertDispatcher == null) {
                return;
            }
            int refundedAfter = Math.max(0, order.getRefundedCents());
            int chargedTotal = paymentOperationRepository.selectList(
                            Wrappers.<PaymentOperation>lambdaQuery()
                                    .eq(PaymentOperation::getOrderId, order.getOrderId())
                                    .eq(PaymentOperation::getStatus, STATUS_COMPLETED)
                                    .in(PaymentOperation::getOperationType, CHARGE, ADJUST_CHARGE))
                    .stream().mapToInt(PaymentOperation::getAmountCents).sum();
            if (chargedTotal <= 0) {
                chargedTotal = order.getTotalAmountCents();
            }
            if (refundedAfter < chargedTotal) {
                return;
            }
            if (invoiceRequestMapper.findActiveByOrderIdForUpdate(order.getOrderId()).isPresent()) {
                opsAlertDispatcher.send("INVOICE_FULL_REFUND_RED_INVERSE", "已开票订单全额退款，需人工红冲",
                        "orderId=" + order.getOrderId() + " 已开票且退款覆盖全部实付，请人工红冲发票");
                log.error("invoiced order fully refunded, manual red-inverse required orderId={}", order.getOrderId());
            }
        } catch (Exception e) {
            log.warn("invoice full-refund alert failed order={}", order.getOrderId(), e);
        }
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
            // M27: 渠道扣款前先以独立事务落 ADJUST INITIATED（稳定渠道单号=orderId+"-ADJ"），
            // 超时/未知异常后的重试先查单确认而不是再次 HTTP
            self.recordAdjustInitiated(locked, deltaCents);
            return new AdjustChargePrep(orderId, locked.getUserId(), deltaCents, idemKey, channel, false, true);
        }
        balanceLedgerService.change(locked.getUserId(), -deltaCents, ADJUST_CHARGE,
                locked.getOrderId(), idemKey, "dispute adjust charge");
        return new AdjustChargePrep(orderId, locked.getUserId(), deltaCents, idemKey, channel, false, false);
    }

    static String adjustInitiatedIdempotencyKey(String orderId, int deltaCents) {
        return "ADJUST_CHARGE:INITIATED:" + orderId + ":" + deltaCents;
    }

    /** M27: 独立事务落「ADJUST 已发起」痕迹（立即提交，渠道已扣而落库失败时可供查单确认）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAdjustInitiated(CabinetOrder order, int deltaCents) {
        String initiatedKey = adjustInitiatedIdempotencyKey(order.getOrderId(), deltaCents);
        if (paymentOperationRepository.findByIdempotencyKey(initiatedKey).isPresent()) {
            return;
        }
        PaymentOperation op = new PaymentOperation();
        op.setOperationId("ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase());
        op.setOrderId(order.getOrderId());
        op.setOperationType(ADJUST_CHARGE);
        op.setAmountCents(deltaCents);
        op.setChannel(order.getPayChannel() != null ? order.getPayChannel() : PayChannels.WECHAT);
        op.setStatus("INITIATED");
        op.setIdempotencyKey(initiatedKey);
        op.setGatewayTradeNo(order.getOrderId() + "-ADJ");
        op.setReason("争议差额渠道扣款已发起（INITIATED）");
        paymentOperationRepository.save(op);
    }

    /** M27: 渠道结果落库后把 INITIATED 标记 FINALIZED（审计用，不参与净额计算）。 */
    private void markAdjustInitiatedFinalized(String orderId, int deltaCents) {
        paymentOperationRepository.findByIdempotencyKey(adjustInitiatedIdempotencyKey(orderId, deltaCents))
                .filter(op -> "INITIATED".equals(op.getStatus()))
                .ifPresent(op -> {
                    op.setStatus("FINALIZED");
                    paymentOperationRepository.updateById(op);
                });
    }

    @Transactional
    public void finalizeAdjustCharge(AdjustChargePrep prep, PayScoreService.ChargeResult charge) {
        CabinetOrder locked = cabinetOrderRepository.findByIdForUpdate(prep.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (isCompleted(prep.idemKey())) {
            markAdjustInitiatedFinalized(prep.orderId(), prep.deltaCents());
            return;
        }
        if (charge != null && !PayChannels.BALANCE.equals(charge.channel())) {
            recordOperation(locked, ADJUST_CHARGE, prep.deltaCents(), charge.channel(), prep.idemKey(),
                    charge.tradeNo(), "dispute adjust charge");
            markAdjustInitiatedFinalized(prep.orderId(), prep.deltaCents());
            log.info("order adjust charge channel={} order={} delta={}",
                    charge.channel(), prep.orderId(), prep.deltaCents());
            return;
        }
        balanceLedgerService.change(locked.getUserId(), -prep.deltaCents(), ADJUST_CHARGE,
                locked.getOrderId(), prep.idemKey(), "dispute adjust charge");
        markAdjustInitiatedFinalized(prep.orderId(), prep.deltaCents());
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

    /** @return 渠道退款状态（微信 PROCESSING/SUCCESS/ABNORMAL；支付宝同步退款视为 SUCCESS）。 */
    private String executeLiveChannelRefund(OrderRefundPrep prep) {
        String outRefundNo = deterministicRefundNo(prep.idemKey());
        String reason = reasonOrDefault(prep.reason());
        if (PayChannels.WECHAT.equalsIgnoreCase(prep.channel())) {
            com.fasterxml.jackson.databind.JsonNode resp = weChatPayClient.createRefund(
                    prep.orderId(), outRefundNo, prep.refundCents(),
                    prep.originalChargeTotalCents(), reason);
            String status = weChatRefundStatus(resp);
            if ("ABNORMAL".equalsIgnoreCase(status)) {
                log.error("wechat refund abnormal order={} outRefundNo={} resp={}",
                        prep.orderId(), outRefundNo, resp);
                try {
                    opsAlertDispatcher.send("WECHAT_REFUND_ABNORMAL", "微信退款异常",
                            "orderId=" + prep.orderId() + " outRefundNo=" + outRefundNo + " 需人工介入");
                } catch (Exception e) {
                    log.warn("ops alert failed wechat refund abnormal order={}", prep.orderId(), e);
                }
            }
            log.info("wechat order refund order={} amount={} total={} refundStatus={} (原路退回零钱)",
                    prep.orderId(), prep.refundCents(), prep.originalChargeTotalCents(), status);
            return status;
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(prep.channel())) {
            alipayPayClient.refund(prep.orderId(), outRefundNo, prep.refundCents(), reason);
            log.info("alipay order refund order={} amount={}", prep.orderId(), prep.refundCents());
            return "SUCCESS";
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "不支持的退款渠道");
    }

    private PayScoreService.ChargeResult executeLiveAdjustCharge(AdjustChargePrep prep) {
        UserInfo user = userInfoRepository.findById(prep.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        String channelOutNo = prep.orderId() + "-ADJ";
        // M27: 已发起过的扣款先查单确认结果，确认无结果才允许重发（同 Idempotency-Key=orderId-ADJ）
        PayScoreService.ChargeResult confirmed = confirmAdjustChargeViaQuery(prep, channelOutNo);
        if (confirmed != null) {
            return confirmed;
        }
        return payScoreService.charge(user, channelOutNo, prep.deltaCents(), reasonOrDefault(null));
    }

    /** M27: 借助网关 queryCharge（H50）确认 INITIATED 扣款的真实结果；无结果返回 null。 */
    private PayScoreService.ChargeResult confirmAdjustChargeViaQuery(AdjustChargePrep prep, String channelOutNo) {
        if (agreementChargeClient == null || !agreementChargeClient.isConfigured()) {
            return null;
        }
        try {
            var response = agreementChargeClient.queryCharge(channelOutNo);
            if (response.isPresent() && response.get().tradeNo() != null && !response.get().tradeNo().isBlank()
                    && response.get().status() != null && "SUCCESS".equalsIgnoreCase(response.get().status())) {
                log.warn("adjust charge confirmed via queryCharge order={} outNo={} tradeNo={}",
                        prep.orderId(), channelOutNo, response.get().tradeNo());
                return new PayScoreService.ChargeResult(prep.preferredChannel(), response.get().tradeNo());
            }
        } catch (Exception e) {
            log.warn("adjust charge query confirm failed order={}: {}", prep.orderId(), e.getMessage());
        }
        return null;
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
     * 微信退款接口要求 total=原支付金额。取本单 COMPLETED CHARGE + ADJUST_CHARGE 合计
     * （H03: 争议加收差额 ADJUST_CHARGE 也是真实扣款，必须计入，否则退款超上限被微信拒绝；
     * 与 {@link PaymentOperationMapper#netCompletedCents(String)} 口径一致）；
     * 无流水时回退到 max(当前订单额, 本次退款额)。
     */
    private int resolveOriginalChargeTotalCents(CabinetOrder order, int refundCents) {
        int charged = paymentOperationRepository.selectList(
                        Wrappers.<PaymentOperation>lambdaQuery()
                                .eq(PaymentOperation::getOrderId, order.getOrderId())
                                .eq(PaymentOperation::getStatus, STATUS_COMPLETED)
                                .in(PaymentOperation::getOperationType, CHARGE, ADJUST_CHARGE))
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
