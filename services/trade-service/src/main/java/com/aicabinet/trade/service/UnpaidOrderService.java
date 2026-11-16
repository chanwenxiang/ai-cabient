package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CancelUnpaidOrderRequest;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.UnpaidOrderActionResultDto;
import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.recognition.RecognitionOutcome;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.aicabinet.trade.wechat.MiniAppTarget;

@Service
public class UnpaidOrderService {
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String ORDER = "ORDER";


    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderService.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final long REMIND_COOLDOWN_MINUTES = 30;

    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final UserInfoMapper userInfoRepository;
    private final AdminAuditLogMapper auditLogRepository;
    private final InventoryService inventoryService;
    private final OrderPaymentService orderPaymentService;
    private final RevenueSplitService revenueSplitService;
    private final MemberService memberService;
    private final CouponService couponService;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final RiskControlService riskControlService;
    private final SystemConfigService systemConfigService;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final SettlementService settlementService;
    private final ConsumerPreauthService consumerPreauthService;
    private final NotificationService notificationService;
    private final DistributedLockService distributedLockService;
    private final ApiRateLimitService apiRateLimitService;
    private final OpsExceptionService opsExceptionService;
    private final UnpaidDunningService unpaidDunningService;
    private final TransactionTemplate requiresNewTx;

    public UnpaidOrderService(CabinetOrderMapper orderRepository,
                              CabinetOrderLineMapper orderLineRepository,
                              UserInfoMapper userInfoRepository,
                              AdminAuditLogMapper auditLogRepository,
                              InventoryService inventoryService,
                              OrderPaymentService orderPaymentService,
                              RevenueSplitService revenueSplitService,
                              MemberService memberService,
                              CouponService couponService,
                              MerchantScopeService merchantScopeService,
                              PermissionService permissionService,
                              AdminAuditService auditService,
                              RiskControlService riskControlService,
                              SystemConfigService systemConfigService,
                              WeChatMiniAppClient weChatMiniAppClient,
                              WeChatMiniAppProperties weChatMiniAppProperties,
                              @Lazy SettlementService settlementService,
                              ConsumerPreauthService consumerPreauthService,
                              NotificationService notificationService,
                              DistributedLockService distributedLockService,
                              ApiRateLimitService apiRateLimitService,
                              OpsExceptionService opsExceptionService,
                              UnpaidDunningService unpaidDunningService,
                              PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.userInfoRepository = userInfoRepository;
        this.auditLogRepository = auditLogRepository;
        this.inventoryService = inventoryService;
        this.orderPaymentService = orderPaymentService;
        this.revenueSplitService = revenueSplitService;
        this.memberService = memberService;
        this.couponService = couponService;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.riskControlService = riskControlService;
        this.systemConfigService = systemConfigService;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.settlementService = settlementService;
        this.consumerPreauthService = consumerPreauthService;
        this.notificationService = notificationService;
        this.distributedLockService = distributedLockService;
        this.apiRateLimitService = apiRateLimitService;
        this.opsExceptionService = opsExceptionService;
        this.unpaidDunningService = unpaidDunningService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 催付：微信订阅消息在事务外发送，避免占用 DB 连接等待渠道。
     *
     * <p>V325：<b>新增短信通道</b>（{@link UnpaidDunningService#remindBySms}）。
     * 原因（CB-003）：微信订阅消息<b>需用户主动订阅</b> ⇒ 最该被催的逃单者恰恰收不到；
     * {@code user_info} 必有手机号（登录要短信验证码），短信是唯一无条件可达通道。
     *
     * <p>微信与短信都尝试：微信已订阅时命中率高，短信是无条件兜底。
     */
    public UnpaidOrderActionResultDto remind(Long operatorId, String orderId) {
        permissionService.requirePermission(operatorId, "ops:order:remind");
        CabinetOrder order = requirePendingScoped(operatorId, orderId);
        if (recentlyReminded(orderId)) {
            return new UnpaidOrderActionResultDto(orderId, order.getStatus(),
                    "30 分钟内已催付，请稍后再试", false, false);
        }
        UserInfo user = userInfoRepository.findById(order.getUserId()).orElse(null);
        boolean sent = false;
        String message;
        if (user == null || user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
            message = "用户未绑定微信，已记催付审计（请线下触达）";
        } else {
            String templateId = weChatMiniAppProperties.subscribeTemplateId();
            if (templateId == null || templateId.isBlank()) {
                templateId = "mock-unpaid-template";
            }
            String amount = String.format("¥%.2f", order.getTotalAmountCents() / 100.0);
            sent = weChatMiniAppClient.sendSubscribeMessage(
                    user.getWxOpenId(),
                    templateId,
                    "pages/orders/orders",
                    Map.of(
                            "thing1", "待支付订单提醒",
                            "amount2", amount,
                            "time3", TIME_FMT.format(Instant.now())
                    ),
                    MiniAppTarget.CONSUMER);
            message = sent ? "催付订阅消息已发送" : "催付发送失败（请检查小程序订阅配置），已记审计";
        }
        // V325：微信没命中（或用户根本没订阅）时走短信兜底 —— 人工催缴 force=true 跳过 24h 节流
        UnpaidDunningService.DunningResult sms = unpaidDunningService.remindBySms(order, true);
        boolean wechatSent = sent;
        if (sms.smsSent()) {
            sent = true;
            message = "催付短信已发送" + (wechatSent ? "（微信订阅消息亦已发送）" : "（微信未订阅，已用短信兜底）");
        } else if (sms.message() != null && !sms.message().isBlank()) {
            message = message + "；短信：" + sms.message();
        }
        auditService.appendLog(operatorId, "ORDER_REMIND", ORDER, orderId, message);
        return new UnpaidOrderActionResultDto(orderId, order.getStatus(), message, sent, false);
    }

    @Transactional
    public UnpaidOrderActionResultDto cancel(Long operatorId, String orderId, CancelUnpaidOrderRequest request) {
        permissionService.requirePermission(operatorId, "ops:order:cancel");
        return runWithOrderPaymentLock(orderId, () -> {
            CabinetOrder order = requirePendingScoped(operatorId, orderId);
            String reason = request.reason().trim();
            restoreInventory(order);
            order.setStatus(STATUS_CANCELLED);
            orderRepository.save(order);
            consumerPreauthService.releaseBySessionId(order.getSessionId());

            boolean blacklist = Boolean.TRUE.equals(request.blacklist());
            if (blacklist && order.getUserId() != null) {
                Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
                riskControlService.addBlacklist(operatorId, order.getUserId(),
                        "待支付关单：" + reason, expires);
            }
            auditService.appendLog(operatorId, "ORDER_CANCEL_UNPAID", ORDER, orderId,
                    reason + (blacklist ? "；已拉黑用户 30 天" : ""));
            log.info("unpaid order cancelled order={} by={} blacklist={}", orderId, operatorId, blacklist);
            // V325（CB-011）：关单是「欠款成立」的判定点 ⇒ 阶梯在此推进，而非下单时。
            // 放在事务内是有意的：阶梯与关单要么都成、要么都回滚，避免「关了单但阶梯没涨」。
            unpaidDunningService.recordUnpaidAndEscalate(order.getUserId());
            return new UnpaidOrderActionResultDto(orderId, STATUS_CANCELLED,
                    blacklist ? "已关单并拉黑用户 30 天" : "待支付订单已关闭，库存已回滚", false, blacklist);
        });
    }

    public OrderReadModel collect(Long operatorId, String orderId) {
        permissionService.requireAnyPermission(operatorId, "ops:order:remind", "ops:order:cancel", "ops:order:refund");
        return runWithOrderPaymentLock(orderId, () -> {
            PreparedCollect prepared = requiresNewTx.execute(status -> prepareForOps(operatorId, orderId));
            if (!prepared.netCovered()) {
                chargeAndFinalize(prepared, null);
            }
            auditService.appendLog(operatorId, "ORDER_COLLECT_UNPAID", ORDER, orderId, "运营代收");
            return settlementService.getOrderBySession(prepared.order().getSessionId());
        });
    }

    /**
     * 消费者补缴待支付订单。
     *
     * @param channel F6：消费者在结算页**显式选择**的支付方式（BALANCE / WECHAT / ALIPAY）；
     *                {@code null}/空白 ⇒ 沿用服务端自动决策，行为与接入前一致。
     *                非空时只按该渠道扣款、**不降级** —— 未就绪返回 412。
     */
    public OrderReadModel collectByUser(Long userId, String orderId, String channel) {
        apiRateLimitService.assertOrderPayAllowed(userId);
        return runWithOrderPaymentLock(orderId, () -> {
            PreparedCollect prepared = requiresNewTx.execute(status -> prepareForUser(userId, orderId, channel));
            if (!prepared.netCovered()) {
                chargeAndFinalize(prepared, channel);
            }
            return settlementService.getOrderBySession(prepared.order().getSessionId());
        });
    }

    @Transactional
    public int autoCancelExpired() {
        // R22-6（四端审计 2026-11-12）：巡检先于关单开关短路执行——字典把
        // order.unpaid.auto_cancel_hours=0 定义为「0=关闭」的受支持配置，运营调 0 只应
        // 关掉自动关单；alertStaleChargePendingOps 是 CHARGE_PENDING（渠道扣款结果未知）
        // 的唯一自动出口，不能搭关单腿的车一起被关（同族对照：催缴腿遇 hours<=0 有 72h 兜底）。
        try {
            alertStaleChargePendingOps();
        } catch (Exception ex) {
            log.warn("stale CHARGE_PENDING alert sweep failed", ex);
        }
        // MA-284：退款同步（微信手工退款，账本 RECHARGE_REFUND）与充值占位同频兜底——
        // 扣账成功但 finalizeChannelPartialRefund 半段丢失的单（§11 残留边界）在这里可见，
        // 不再「静默等下一次对账」。
        try {
            alertRefundSyncNotAdvancedOps();
        } catch (Exception ex) {
            log.warn("refund-sync not-advanced alert sweep failed", ex);
        }
        int hours = systemConfigService.getInt(SystemConfigService.UNPAID_AUTO_CANCEL_HOURS, 48);
        if (hours <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<CabinetOrder> expired = orderRepository.findByStatusAndCreatedAtBefore(STATUS_PENDING, cutoff, 500);
        boolean autoBlacklist = systemConfigService.getBoolean(SystemConfigService.UNPAID_AUTO_BLACKLIST, false);
        int n = 0;
        for (CabinetOrder order : expired) {
            try {
                if (cancelSingleExpiredOrder(order, hours, autoBlacklist)) {
                    n++;
                }
            } catch (Exception ex) {
                log.warn("auto cancel unpaid failed order={}", order.getOrderId(), ex);
            }
        }
        if (n > 0) {
            log.info("auto cancelled unpaid orders count={} hours={}", n, hours);
        }
        return n;
    }

    /**
     * 🔴 V325 自动短信催缴（CB-003）：扫 PENDING 欠款单，逐笔发催缴短信。
     *
     * <p>竞品依据：服务商侧实操是「每天自动重扣 + 每天催款短信」；支付宝芝麻先享
     * 是「首次发起 7 天内未扣成即转已逾期」⇒ 催缴窗口应在超时关单（默认 48h）**之前**，
     * 否则单子已被关掉、用户也没东西可缴了。
     *
     * <p>所以这里的取单窗口 = {@code [now - 关单阈值, now)} 之前**已存在**的 PENDING 单，
     * 且逐笔由 {@code UnpaidDunningService} 做 24h 节流。
     *
     * @return 本次实际发出短信的笔数
     */
    public int autoRemindUnpaidOrders() {
        int hours = systemConfigService.getInt(SystemConfigService.UNPAID_AUTO_CANCEL_HOURS, 48);
        if (hours <= 0) {
            // 自动关单已关闭 ⇒ 单子会一直 PENDING。此时仍需催缴，但不再以关单阈值当窗口上限。
            hours = 72;
        }
        // 只催「刚超过最短催缴窗口」的单：0.5×阈值 起算，避免刚下单就被催（用户还没走到结算）
        int windowHours = Math.max(1, hours / 2);
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        List<CabinetOrder> candidates = orderRepository.findByStatusAndCreatedAtBefore(
                STATUS_PENDING, Instant.now(), 200);
        int sent = 0;
        for (CabinetOrder order : candidates) {
            if (order.getCreatedAt() == null || order.getCreatedAt().isAfter(since)) {
                continue;
            }
            try {
                // force=false：走 24h 节流，避免同一用户被反复短信轰炸（CB-004 法律红线）
                if (unpaidDunningService.remindBySms(order, false).smsSent()) {
                    sent++;
                }
            } catch (Exception ex) {
                // 单笔失败不能中断整批：其余用户仍需被催到
                log.warn("auto dunning failed order={}", order.getOrderId(), ex);
            }
        }
        if (sent > 0) {
            log.info("auto dunning sent count={} windowHours={}", sent, windowHours);
        }
        return sent;
    }

    /** CHARGE_PENDING 超过该分钟数仍未收口即告警转人工（正常回执为秒级）。 */
    static final int CHARGE_PENDING_ALERT_MINUTES = 60;

    /**
     * 渠道扣款痕迹超时巡检：CHARGE_PENDING 长期不收口 = 渠道回执丢失或进程中断，
     * 订单卡在无主状态。发 HIGH 告警转人工核对，不自动改单（金额未知，禁止程序臆断）。
     *
     * @return 本次告警单数
     */
    public int alertStaleChargePendingOps() {
        Instant cutoff = Instant.now().minus(CHARGE_PENDING_ALERT_MINUTES, ChronoUnit.MINUTES);
        List<com.aicabinet.trade.domain.PaymentOperation> stale =
                orderPaymentService.findStaleChargePending(cutoff, 20);
        for (com.aicabinet.trade.domain.PaymentOperation op : stale) {
            opsExceptionService.report(
                    "CHARGE_PENDING_STALE",
                    "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            null, null, op.getOrderId(), op.getUserId()),
                    "渠道扣款回执超时",
                    "订单 " + op.getOrderId() + " 的 CHARGE_PENDING 已超过 "
                            + CHARGE_PENDING_ALERT_MINUTES + " 分钟未收口（渠道="
                            + op.getChannel() + "，金额(分)=" + op.getAmountCents()
                            + "），请人工核对渠道订单状态后处置");
            log.warn("stale CHARGE_PENDING op orderId={} channel={} amount={}",
                    op.getOrderId(), op.getChannel(), op.getAmountCents());
        }
        return stale.size();
    }

    /**
     * MA-284：退款同步「扣账成功、单据未推进」的告警巡检（与 CHARGE_PENDING 同频）。
     * 判据见 {@code PaymentOperationMapper#findRefundSyncNotAdvanced}；只告警不自动修。
     */
    public int alertRefundSyncNotAdvancedOps() {
        java.util.List<java.util.Map<String, Object>> gaps =
                orderPaymentService.findRefundSyncNotAdvanced(20);
        for (java.util.Map<String, Object> gap : gaps) {
            String orderId = String.valueOf(gap.get("orderId"));
            Object userId = gap.get("userId");
            long debited = ((Number) gap.get("debitedCents")).longValue();
            long refunded = ((Number) gap.get("refundedCents")).longValue();
            opsExceptionService.report(
                    "REFUND_SYNC_NOT_ADVANCED",
                    "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            null, null, orderId, userId instanceof Number n ? n.longValue() : null),
                    "退款同步未推进单据",
                    "订单 " + orderId + " 的退款流水合计 " + debited + " 分大于订单 refunded_cents "
                            + refunded + " 分：扣账已入账本但单据推进半段丢失（MA-269 残留边界），"
                            + "请人工核对渠道退款结果后补推进");
            log.warn("refund sync not advanced orderId={} debited={} refunded={}", orderId, debited, refunded);
        }
        return gaps.size();
    }

    private boolean cancelSingleExpiredOrder(CabinetOrder order, int hours, boolean autoBlacklist) {
        // F1-C：净额>0 的 PENDING 单禁止自动取消——取消=货回库但冲抵款已扣（用户白付），
        // 必须人工退款处置。转 HIGH 异常，单保持 PENDING。
        if (orderPaymentService.netCompletedCents(order.getOrderId()) > 0) {
            opsExceptionService.report(
                    "UNPAID_CANCEL_NET_BLOCKED",
                    "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            null, null, order.getOrderId(), order.getUserId()),
                    "待支付单存在净入账，禁止自动取消",
                    "订单 " + order.getOrderId() + " 净额 "
                            + orderPaymentService.netCompletedCents(order.getOrderId())
                            + " 分 > 0（预授权冲抵保留），自动取消会造成已扣款+已回库；请人工核对后退款处置");
            return false;
        }
        CabinetOrder cancelled = runWithOrderPaymentLock(order.getOrderId(), () -> {
            CabinetOrder locked = orderRepository.findByIdForUpdate(order.getOrderId()).orElse(null);
            if (locked == null || !STATUS_PENDING.equals(locked.getStatus())) {
                return null;
            }
            restoreInventory(locked);
            locked.setStatus(STATUS_CANCELLED);
            orderRepository.save(locked);
            consumerPreauthService.releaseBySessionId(locked.getSessionId());
            if (autoBlacklist && locked.getUserId() != null) {
                riskControlService.addBlacklist(0L, locked.getUserId(),
                        "待支付超时自动关单", Instant.now().plus(7, ChronoUnit.DAYS));
            }
            auditService.appendLog(0L, "ORDER_AUTO_CANCEL_UNPAID", ORDER, locked.getOrderId(),
                    "超时 " + hours + " 小时自动关单；是否拉黑=" + (autoBlacklist ? "是" : "否"));
            return locked;
        });
        return cancelled != null;
    }

    private PreparedCollect prepareForOps(Long operatorId, String orderId) {
        return prepareOrder(requirePendingScoped(operatorId, orderId), null);
    }

    private PreparedCollect prepareForUser(Long userId, String orderId, String requestedChannel) {
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
        }
        return prepareOrder(order, requestedChannel);
    }

    /** B1-B 段1：短事务内校验 + 选券落单 + 定腿；不发起渠道 HTTP。 */
    private PreparedCollect prepareOrder(CabinetOrder order, String requestedChannel) {
        hydrate(order);
        int alreadyPaidCents = orderPaymentService.netCompletedCents(order.getOrderId());
        if (alreadyPaidCents >= order.getTotalAmountCents()) {
            closeNetCovered(order, alreadyPaidCents);
            return new PreparedCollect(order, null, true);
        }
        OrderPaymentService.ChargePlan plan = orderPaymentService.resolveChargePlan(order, requestedChannel);
        if (plan == null || plan.leg() != OrderPaymentService.ChargeLeg.COMPLETED) {
            // 创建 PENDING 时未占券；补扣时再选最优券后扣款并核销
            applyBestCouponForCollect(order);
            plan = orderPaymentService.resolveChargePlan(order, requestedChannel);
            orderRepository.save(order);
        }
        return new PreparedCollect(order, plan, false);
    }

    /** 净额已覆盖全额：同事务直接收口（无渠道 HTTP），保持原语义。 */
    private void closeNetCovered(CabinetOrder order, int alreadyPaidCents) {
        order.setStatus("PAID");
        // F-9（CB-057）：这一支没有新扣款，但冲抵流水就是实收 ⇒ 同样要冻结毛额
        orderPaymentService.freezePaidAmount(order);
        orderRepository.save(order);
        if (order.getCouponId() == null) {
            orderRepository.clearOrderCouponSelection(
                    order.getOrderId(), order.getTotalAmountCents(), Instant.now());
        }
        revenueSplitService.recordSplit(order);
        unpaidDunningService.onUnpaidCleared(order.getUserId());
        auditService.appendLog(0L, "ORDER_COLLECT_NET_COVERED", ORDER, order.getOrderId(),
                "净额 " + alreadyPaidCents + " 分已覆盖应付，直接结单");
    }

    /**
     * B1-B 段2/3：余额腿与 PAID 同事务（不足整段回滚，保持既有语义）；
     * 渠道腿 HTTP 在事务外，成功后再开短事务收口。
     */
    private void chargeAndFinalize(PreparedCollect prepared, String requestedChannel) {
        CabinetOrder order = prepared.order();
        OrderPaymentService.ChargePlan plan = prepared.plan();
        boolean balanceLeg = plan != null && plan.leg() == OrderPaymentService.ChargeLeg.BALANCE;
        boolean channelLeg = plan != null && plan.leg() == OrderPaymentService.ChargeLeg.CHANNEL;
        CabinetOrder finalized;
        if (balanceLeg) {
            finalized = requiresNewTx.execute(status -> {
                orderPaymentService.chargeOrderWithPlan(order, requestedChannel, plan);
                return finalizeCollected(order.getOrderId());
            });
        } else if (channelLeg) {
            orderPaymentService.chargeOrderWithPlan(order, requestedChannel, plan);
            finalized = requiresNewTx.execute(status -> finalizeCollected(order.getOrderId()));
        } else {
            orderPaymentService.chargeOrder(order, requestedChannel);
            finalized = requiresNewTx.execute(status -> finalizeCollected(order.getOrderId()));
        }
        afterCollected(finalized);
    }

    private CabinetOrder finalizeCollected(String orderId) {
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        order.setStatus("PAID");
        // F-9（CB-057）：补扣成功 ⇒ 冻结实收毛额（含此前的开门冲抵行）
        orderPaymentService.freezePaidAmount(order);
        orderRepository.save(order);
        // R2-D1：save=updateById 跳过 null ⇒ 清券列必须走显式 UPDATE
        if (order.getCouponId() == null) {
            orderRepository.clearOrderCouponSelection(
                    order.getOrderId(), order.getTotalAmountCents(), Instant.now());
        }
        if (order.getCouponId() != null && order.getCouponDiscountCents() > 0) {
            couponService.markUsed(
                    order.getUserId(),
                    order.getCouponId(),
                    order.getOrderId(),
                    order.getDeviceId(),
                    order.getCouponDiscountCents());
        }
        revenueSplitService.recordSplit(order);
        unpaidDunningService.onUnpaidCleared(order.getUserId());
        // 渠道成功/自动对账都要释放开门预授权；capture/已释放场景幂等 no-op
        if (order.getSessionId() != null) {
            consumerPreauthService.releaseBySessionId(order.getSessionId());
        }
        // 最后才终结痕迹：前面任一步失败 ⇒ 痕迹保留，reconciler 可重试
        orderPaymentService.finalizeChargePending(orderId, order.getPayTradeNo());
        return order;
    }

    /**
     * B1-B2：reconciler 确认渠道 SUCCESS 后补收口。渠道流水先落（幂等键同主链），
     * 再短事务置 PAID/freeze/split/markUsed；订单状态或金额异常只记录+告警，不擅自改单。
     */
    public boolean reconcileChargePending(String orderId, int traceTotalCents, String channel,
                                          int chargeCents, String tradeNo) {
        return runWithOrderPaymentLock(orderId, () -> {
            if (!orderPaymentService.hasChargePending(orderId)) {
                return false;
            }
            CabinetOrder order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return false;
            }
            boolean stateAbnormal = !STATUS_PENDING.equals(order.getStatus())
                    && !"PAID".equals(order.getStatus());
            boolean amountMismatch = order.getTotalAmountCents() != traceTotalCents;
            orderPaymentService.recordReconciledCharge(order, traceTotalCents, channel, chargeCents, tradeNo);
            if (stateAbnormal) {
                orderPaymentService.finalizeChargePending(orderId, tradeNo);
                reportChargePendingAbnormal(order, chargeCents, "订单状态 " + order.getStatus());
                return false;
            }
            if (amountMismatch) {
                orderPaymentService.finalizeChargePending(orderId, tradeNo);
                reportChargePendingAbnormal(order, chargeCents,
                        "订单总额 " + order.getTotalAmountCents() + " 分 ≠ 痕迹总额 " + traceTotalCents + " 分");
                return false;
            }
            CabinetOrder finalized = requiresNewTx.execute(status -> finalizeCollected(orderId));
            afterCollected(finalized);
            return true;
        });
    }

    private void reportChargePendingAbnormal(CabinetOrder order, int chargeCents, String detail) {
        opsExceptionService.report(
                "CHARGE_PENDING_RECONCILE_MANUAL",
                "HIGH",
                new OpsExceptionService.ExceptionReport.ExceptionRefs(
                        null, null, order.getOrderId(), order.getUserId()),
                "渠道扣款成功但无法自动收口",
                "订单 " + order.getOrderId() + " 渠道已确认扣款 " + chargeCents + " 分（" + detail
                        + "），未自动改单，请人工核对");
    }

    private void afterCollected(CabinetOrder order) {
        try {
            memberService.onOrderPaid(order.getUserId(), order.getTotalAmountCents(), order.getOrderId());
        } catch (Exception ex) {
            log.warn("member stats on collect failed order={}", order.getOrderId(), ex);
        }
        try {
            notificationService.notifyConsumer(
                    order.getUserId(),
                    "order_paid",
                    Map.of("orderId", order.getOrderId(), "amount", yuan(order.getTotalAmountCents())),
                    ORDER,
                    order.getOrderId());
        } catch (Exception ex) {
            log.warn("order paid notification on collect failed order={}", order.getOrderId(), ex);
        }
    }

    private record PreparedCollect(CabinetOrder order, OrderPaymentService.ChargePlan plan, boolean netCovered) {}

    private static String yuan(int cents) {
        return java.math.BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private CouponService.BestCoupon applyBestCouponForCollect(CabinetOrder order) {
        if (order.getUserId() == null || order.getTotalAmountCents() <= 0) {
            return null;
        }
        hydrate(order);
        int subtotal = order.getLines().stream()
                .mapToInt(CabinetOrderLine::getLineAmountCents)
                .sum();
        if (subtotal <= 0) {
            subtotal = order.getOriginalAmountCents() > 0
                    ? order.getOriginalAmountCents()
                    : order.getTotalAmountCents();
        }
        order.setOriginalAmountCents(subtotal);
        // CB-082（用户 2026-11-01 拍板「活动优先」）：活动价订单禁用券。
        // 结算主链在 SettlementOrderFinalizeService 已有同一道闸；补扣/代收链此前漏抄，
        // 会在活动价上再叠一张券（少收一张券面额）。
        if (order.getActivityDiscountCents() > 0) {
            order.setCouponId(null);
            order.setCouponDiscountCents(0);
            order.setTotalAmountCents(subtotal);
            return null;
        }
        if (order.getCouponId() != null) {
            int discount = couponService.discountForOrderCoupon(order.getCouponId(), subtotal);
            if (discount <= 0) {
                order.setCouponId(null);
                order.setCouponDiscountCents(0);
                order.setTotalAmountCents(subtotal);
                return null;
            }
            order.setCouponDiscountCents(discount);
            order.setTotalAmountCents(Math.max(0, subtotal - discount));
            return new CouponService.BestCoupon(order.getCouponId(), discount, null);
        }
        order.setTotalAmountCents(subtotal);
        // 🔴 V319：传 deviceId，否则范围判定被跳过（同 SettlementOrderFinalizeService）
        var best = couponService.selectBestCoupon(order.getUserId(), subtotal, order.getDeviceId());
        if (best.isEmpty()) {
            return null;
        }
        var pick = best.get();
        int discount = Math.min(pick.discountCents(), subtotal);
        order.setCouponId(pick.couponId());
        order.setCouponDiscountCents(discount);
        order.setTotalAmountCents(Math.max(0, subtotal - discount));
        return pick;
    }

    private void restoreInventory(CabinetOrder order) {
        hydrate(order);
        if (!order.isInventoryDeducted() || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }
        List<RecognizedItem> items = order.getLines().stream()
                .map(l -> new RecognizedItem(l.getSkuId(), l.getQuantity(), 1f))
                .toList();
        Map<String, String> batchBySku = order.getLines().stream()
                .filter(l -> l.getBatchNo() != null && !l.getBatchNo().isBlank())
                .collect(Collectors.toMap(CabinetOrderLine::getSkuId, CabinetOrderLine::getBatchNo, (a, b) -> a));
        inventoryService.restoreForOrder(order.getDeviceId(), items, batchBySku, order.getOrderId());
        order.setInventoryDeducted(false);
    }

    private CabinetOrder requirePendingScoped(Long operatorId, String orderId) {
        CabinetOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, order.getDeviceId());
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
        }
        return order;
    }

    private void hydrate(CabinetOrder order) {
        if (order.getLines() == null || order.getLines().isEmpty()) {
            order.setLines(new ArrayList<>(orderLineRepository.findByOrderId(order.getOrderId())));
        }
    }

    private boolean recentlyReminded(String orderId) {
        Instant since = Instant.now().minus(REMIND_COOLDOWN_MINUTES, ChronoUnit.MINUTES);
        List<AdminAuditLog> logs = auditLogRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(ORDER, orderId);
        for (int i = logs.size() - 1; i >= 0; i--) {
            AdminAuditLog row = logs.get(i);
            if ("ORDER_REMIND".equals(row.getAction())
                    && row.getCreatedAt() != null
                    && row.getCreatedAt().isAfter(since)) {
                return true;
            }
        }
        return false;
    }

    private <T> T runWithOrderPaymentLock(String orderId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(OrderPaymentService.orderPaymentLockKey(orderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单支付处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException | BalanceInsufficientException e) {
            // 原样穿透：同 OrderPaymentService（审计 P1-1），包成 500 会毒化事务并打断 F1-B 信号链
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(OrderPaymentService.orderPaymentLockKey(orderId));
        }
    }
}
