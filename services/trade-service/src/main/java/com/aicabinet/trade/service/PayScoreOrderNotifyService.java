package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PayScoreOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PayScoreOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 微信支付分「订单事件」回调处理（CB-064 四步之④的收尾）。
 *
 * <p>完结后微信进入 {@code USER_PAYING} 异步扣款并自动催收（平台侧），本地闭环：
 * <ul>
 *   <li>{@code DONE}（扣款成功）→ 订单置 PAID + 补 CHARGE 流水 + 清欠款计数；</li>
 *   <li>{@code USER_PAYING}（待支付）→ 维持 PENDING（欠款闸已拦下次开门）+ 短信提醒（内部限频）；</li>
 *   <li>{@code EXPIRED / REVOKED} → 记一次欠款并升级催缴阶梯（{@link UnpaidDunningService}）。</li>
 * </ul>
 * 事件体与预期不符（无主回调）只记 WARN 不抛错——回 204 让微信停止重推。
 * 独立于 {@link PayScoreService}：后者已被 OrderPaymentService 依赖，本类只进不出的单向依赖防环。</p>
 */
@Service
public class PayScoreOrderNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PayScoreOrderNotifyService.class);

    /** R20-8：订单终态——收到迟到 DONE 不得回写成 PAID。 */
    private static final java.util.Set<String> TERMINAL_ORDER_STATUS =
            java.util.Set.of("REFUNDED", "PARTIAL_REFUNDED", "CANCELLED");

    /**
     * R20-P2-5：**非终态**的欠款类事件。DONE（扣款成功）之后收到这些一律忽略——
     * 微信支付分的订单态在扣款完成后仍可能因异步补偿投递 USER_PAYING/EXPIRED，
     * 旧实现无条件 {@code recordUnpaidAndEscalate} ⇒ 已扣款用户被记欠款、升级催缴阶梯、
     * 并被欠款闸拦下一次开门（钱已到账却按欠款处置）。
     */
    private static final java.util.Set<String> DUNNING_STATES =
            java.util.Set.of("USER_PAYING", "EXPIRED", "REVOKED");

    private final PayScoreOrderMapper payScoreOrderMapper;
    private final CabinetOrderMapper cabinetOrderMapper;
    private final PaymentOperationMapper paymentOperationMapper;
    private final UnpaidDunningService unpaidDunningService;
    private final OpsExceptionService opsExceptionService;
    private final RevenueSplitService revenueSplitService;
    private final MemberService memberService;
    private final NotificationService notificationService;

    public PayScoreOrderNotifyService(PayScoreOrderMapper payScoreOrderMapper,
                                      CabinetOrderMapper cabinetOrderMapper,
                                      PaymentOperationMapper paymentOperationMapper,
                                      UnpaidDunningService unpaidDunningService,
                                      OpsExceptionService opsExceptionService,
                                      RevenueSplitService revenueSplitService,
                                      MemberService memberService,
                                      NotificationService notificationService) {
        this.payScoreOrderMapper = payScoreOrderMapper;
        this.cabinetOrderMapper = cabinetOrderMapper;
        this.paymentOperationMapper = paymentOperationMapper;
        this.unpaidDunningService = unpaidDunningService;
        this.opsExceptionService = opsExceptionService;
        this.revenueSplitService = revenueSplitService;
        this.memberService = memberService;
        this.notificationService = notificationService;
    }

    /** 是否订单事件（区别于授权事件）：带 out_order_no。 */
    public static boolean isOrderEvent(JsonNode event) {
        return event != null && !event.path("out_order_no").asText("").isBlank();
    }

    @Transactional
    public void handleOrderNotify(JsonNode event) {
        String outOrderNo = event.path("out_order_no").asText("");
        PayScoreOrder po = payScoreOrderMapper.selectList(
                        new LambdaQueryWrapper<PayScoreOrder>()
                                .eq(PayScoreOrder::getOutOrderNo, outOrderNo))
                .stream().findFirst().orElse(null);
        if (po == null) {
            log.warn("wechat payscore order notify unmatched outOrderNo={}", outOrderNo);
            return;
        }
        String state = firstNonBlank(event.path("state").asText(""),
                event.path("collection").path("state").asText(""));
        if (state.isBlank()) {
            log.warn("wechat payscore order notify without state outOrderNo={} keys={}",
                    outOrderNo, event.fieldNames().toString());
            return;
        }
        CabinetOrder order = po.getOrderId() == null ? null
                : cabinetOrderMapper.selectById(po.getOrderId());
        // R20-P2-5（2026-11-12）：订单态**只进不退**，两条判据任一命中即整段忽略（不写库、不催缴、不记欠款）：
        //   ① 本地已收 DONE（钱已到账）⇒ 迟到的 USER_PAYING/EXPIRED/REVOKED 属于异步补偿投递；
        //   ② 订单本身已 PAID ⇒ 即使快照因历史脏数据不是 DONE，实收也是权威。
        // 早退必须放在 setOrderState 之前：否则 DONE 会被倒退成 EXPIRED，查单与对账据此误判。
        if (DUNNING_STATES.contains(state)) {
            String paidEvidence = "DONE".equals(po.getOrderState()) ? "payscoreState=DONE"
                    : (order != null && "PAID".equals(order.getStatus()) ? "orderStatus=PAID" : null);
            if (paidEvidence != null) {
                log.warn("wechat payscore order {} late state={} ignored ({}), no dunning",
                        po.getOutOrderNo(), state, paidEvidence);
                return;
            }
        }
        po.setOrderState(state);
        payScoreOrderMapper.updateById(po);
        switch (state) {
            case "DONE" -> handlePaid(po, order, event);
            case "USER_PAYING" -> {
                if (order != null) {
                    unpaidDunningService.remindBySms(order, false);
                }
            }
            case "EXPIRED", "REVOKED" -> {
                if (order != null) {
                    unpaidDunningService.recordUnpaidAndEscalate(order.getUserId());
                    log.warn("wechat payscore order {} state={} escalated userId={}",
                            outOrderNo, state, order.getUserId());
                }
            }
            default -> log.debug("wechat payscore order notify state={} outOrderNo={} (no-op)", state, outOrderNo);
        }
    }

    private void handlePaid(PayScoreOrder po, CabinetOrder order, JsonNode event) {
        if (order == null) {
            log.warn("wechat payscore DONE without local order outOrderNo={} poOrderId={}",
                    po.getOutOrderNo(), po.getOrderId());
            return;
        }
        if ("PAID".equals(order.getStatus())) {
            return;
        }
        // R20-8（2026-11-12）：原早退条件只有 PAID ⇒ 已退款/已取消的订单收到迟到 DONE 会被
        // 改回 PAID（DisputeService 又只挡 REFUNDED），同一笔单可以二次退款。订单状态是单向机：
        // 终态收到 DONE 一律不动订单，只落 HIGH 痕迹交人工（钱可能真到了，必须有人核对）。
        if (TERMINAL_ORDER_STATUS.contains(order.getStatus())) {
            reportTerminalResurrect(po, order, event);
            return;
        }
        int amount = po.getActualAmountCents() != null ? po.getActualAmountCents() : order.getTotalAmountCents();
        String tradeNo = firstNonBlank(event.path("transaction_id").asText(""),
                event.path("out_order_no").asText(""));
        String idemKey = "CHARGE:" + order.getOrderId() + ":" + amount;
        if (paymentOperationMapper.findByIdempotencyKey(idemKey).isEmpty()) {
            PaymentOperation op = new PaymentOperation();
            op.setOperationId("CHG-PAYSCORE-" + po.getOutOrderNo());
            op.setOrderId(order.getOrderId());
            op.setUserId(order.getUserId());
            op.setOperationType("CHARGE");
            op.setAmountCents(amount);
            op.setChannel("WECHAT");
            op.setStatus("COMPLETED");
            op.setIdempotencyKey(idemKey);
            op.setGatewayTradeNo(tradeNo);
            op.setReason("微信支付分扣款到账（回调）");
            paymentOperationMapper.insert(op);
        }
        order.setStatus("PAID");
        order.setPayTradeNo(tradeNo);
        cabinetOrderMapper.updateById(order);
        // R20-2（2026-11-13）：分账/商户钱包入账的**实收确认点**就是这里。结算时该单是
        // PENDING（微信异步扣款，钱还没进来），RevenueSplitService 只建分账行不入账；
        // 现在订单已 PAID ⇒ 重进 recordSplit 走「已存在」支补入账（creditIfAbsent 以
        // splitId 为幂等键，重复回调无害）。刻意**不**包 try：资金动作失败要让本回调事务
        // 整体回滚、订单退回未实收、微信按策略重试——吞掉就留下「钱到了、商户没入账」。
        revenueSplitService.recordSplit(order);
        // R20-2：结算时对未实收单刻意推迟的两件善后（会员消费统计/返积分、order_paid 通知）
        // 在实收确认这里补做。金额仍按订单总额而非本次实收额，与补缴路径
        // （UnpaidOrderService.collect 传 totalAmountCents）逐字同口径——本批改的是**时点**，
        // 不顺手改**基数**，否则会把「发放基数」和「入账基数」又拆成两套。
        try {
            memberService.onOrderPaid(order.getUserId(), order.getTotalAmountCents(), order.getOrderId());
        } catch (Exception e) {
            log.warn("member stats on payscore DONE failed order={}", order.getOrderId(), e);
        }
        try {
            notificationService.notifyConsumer(
                    order.getUserId(),
                    "order_paid",
                    java.util.Map.of("orderId", order.getOrderId(), "amount",
                            java.math.BigDecimal.valueOf(order.getTotalAmountCents(), 2)
                                    .stripTrailingZeros().toPlainString()),
                    "ORDER",
                    order.getOrderId());
        } catch (Exception e) {
            log.warn("order paid notification on payscore DONE failed order={}", order.getOrderId(), e);
        }
        unpaidDunningService.onUnpaidCleared(order.getUserId());
        log.info("wechat payscore order PAID order={} amount={} tradeNo={}", order.getOrderId(), amount, tradeNo);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** R20-8：终态订单收到 DONE —— 不动订单状态，落 HIGH 异常 + error 日志供人工核对。 */
    private void reportTerminalResurrect(PayScoreOrder po, CabinetOrder order, JsonNode event) {
        String tradeNo = firstNonBlank(event.path("transaction_id").asText(""),
                event.path("out_order_no").asText(""));
        String detail = "orderId=" + order.getOrderId() + " status=" + order.getStatus()
                + " outOrderNo=" + po.getOutOrderNo() + " tradeNo=" + tradeNo
                + " 支付分回调 DONE 迟到，订单已处于终态，未改写状态；请人工核对渠道是否真实扣款并处置";
        log.error("wechat payscore DONE on terminal order, status NOT changed: {}", detail);
        if (opsExceptionService == null) {
            return;
        }
        try {
            opsExceptionService.report("PAYSCORE_DONE_ON_TERMINAL_ORDER", "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            order.getDeviceId(), order.getSessionId(), order.getOrderId(), order.getUserId()),
                    "支付分扣款到账但订单已终态", detail);
        } catch (Exception e) {
            log.warn("ops exception report failed order={}", order.getOrderId(), e);
        }
    }

    /** 供控制器路由的只读探测（避免把 mapper 暴露出去）。 */
    public List<String> handledStates() {
        return List.of("CREATED", "USER_CONFIRM", "DOING", "USER_PAYING", "DONE", "EXPIRED", "REVOKED");
    }
}
