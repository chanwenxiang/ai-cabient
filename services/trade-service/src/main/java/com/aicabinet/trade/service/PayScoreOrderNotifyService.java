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

    private final PayScoreOrderMapper payScoreOrderMapper;
    private final CabinetOrderMapper cabinetOrderMapper;
    private final PaymentOperationMapper paymentOperationMapper;
    private final UnpaidDunningService unpaidDunningService;

    public PayScoreOrderNotifyService(PayScoreOrderMapper payScoreOrderMapper,
                                      CabinetOrderMapper cabinetOrderMapper,
                                      PaymentOperationMapper paymentOperationMapper,
                                      UnpaidDunningService unpaidDunningService) {
        this.payScoreOrderMapper = payScoreOrderMapper;
        this.cabinetOrderMapper = cabinetOrderMapper;
        this.paymentOperationMapper = paymentOperationMapper;
        this.unpaidDunningService = unpaidDunningService;
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
        po.setOrderState(state);
        payScoreOrderMapper.updateById(po);
        CabinetOrder order = po.getOrderId() == null ? null
                : cabinetOrderMapper.selectById(po.getOrderId());
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
        unpaidDunningService.onUnpaidCleared(order.getUserId());
        log.info("wechat payscore order PAID order={} amount={} tradeNo={}", order.getOrderId(), amount, tradeNo);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** 供控制器路由的只读探测（避免把 mapper 暴露出去）。 */
    public List<String> handledStates() {
        return List.of("CREATED", "USER_CONFIRM", "DOING", "USER_PAYING", "DONE", "EXPIRED", "REVOKED");
    }
}
