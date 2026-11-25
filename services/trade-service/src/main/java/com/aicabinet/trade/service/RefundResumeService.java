package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.util.BizIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 退款异步续跑的唯一编排点：支付流水完成后只补调用方本地下游，绝不再次发起渠道退款。
 *
 * <p>上下文在 REFUND_INITIATED / REFUND_PROCESSING 时即持久化；同步 SUCCESS 仍由原调用方
 * 收尾，但上下文保留为 PENDING，进程崩溃或下游落库失败时由分钟级扫描安全补跑。
 * 下游全额/按行 finalizer 自身幂等，重复补跑不会重复回库、退券或冲分账。</p>
 */
@Service
public class RefundResumeService {

    private static final Logger log = LoggerFactory.getLogger(RefundResumeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONTEXT_VERSION = 1;

    private final PaymentOperationMapper paymentOperationMapper;
    private final CabinetOrderMapper orderRepository;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final SettlementWaiveRefundService waiveRefundService;
    private final SettlementPartialRefundService partialRefundService;

    public RefundResumeService(PaymentOperationMapper paymentOperationMapper,
                               CabinetOrderMapper orderRepository,
                               OpsAlertDispatcher opsAlertDispatcher,
                               @Lazy SettlementWaiveRefundService waiveRefundService,
                               @Lazy SettlementPartialRefundService partialRefundService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.orderRepository = orderRepository;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.waiveRefundService = waiveRefundService;
        this.partialRefundService = partialRefundService;
    }

    public record ContextLine(String skuId, int quantity, Boolean restoreInventory) {
        public OrderRefundRequest.PartialRefundLine toRequestLine() {
            return new OrderRefundRequest.PartialRefundLine(skuId, quantity, restoreInventory);
        }
    }

    /** 订单退款调用方上下文；resumeToken 用于同步调用方收尾后精确标记，避免误标同订单其它退款。 */
    public record OrderRefundContext(
            int version,
            String kind,
            String resumeToken,
            String orderId,
            boolean restoreInventory,
            List<ContextLine> refundLines,
            int totalCents,
            String reason,
            String downstreamStatus) {
    }

    /** 充值退款上下文；closeAfterSuccess 区分整单退款结案与余额退款切片。 */
    public record RechargeRefundContext(
            int version,
            String kind,
            String rechargeOrderId,
            String outRefundNo,
            int amountCents,
            int bonusRevokedCents,
            boolean closeAfterSuccess,
            String reason,
            String localStatus) {
    }

    public static OrderRefundContext fullContext(String orderId, boolean restoreInventory,
                                                  int totalCents, String reason) {
        return new OrderRefundContext(CONTEXT_VERSION, "FULL", fullToken(orderId), orderId,
                restoreInventory, List.of(), Math.max(0, totalCents), reason, "PENDING");
    }

    public static OrderRefundContext partialContext(String orderId,
                                                     List<OrderRefundRequest.PartialRefundLine> lines,
                                                     boolean defaultRestore,
                                                     int totalCents,
                                                     String reason) {
        List<ContextLine> contextLines = lines.stream()
                .map(line -> new ContextLine(line.skuId(), line.quantity(), line.restoreInventory()))
                .toList();
        return new OrderRefundContext(CONTEXT_VERSION, "PARTIAL",
                partialToken(orderId, lines), orderId, defaultRestore, contextLines,
                Math.max(0, totalCents), reason, "PENDING");
    }

    public static RechargeRefundContext rechargeContext(String rechargeOrderId, String outRefundNo,
                                                         int amountCents, int bonusRevokedCents,
                                                         boolean closeAfterSuccess, String reason) {
        return new RechargeRefundContext(CONTEXT_VERSION, "RECHARGE", rechargeOrderId, outRefundNo,
                Math.max(0, amountCents), Math.max(0, bonusRevokedCents), closeAfterSuccess,
                reason, "PENDING");
    }

    public static String fullToken(String orderId) {
        return "FULL:" + orderId;
    }

    public static String partialToken(String orderId, List<OrderRefundRequest.PartialRefundLine> lines) {
        return "PARTIAL:" + orderId + ":" + SettlementPartialRefundService.refundLinesFingerprint(lines);
    }

    public static String orderContextJson(OrderRefundContext context) {
        if (context == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalStateException("退款续跑上下文序列化失败 order=" + context.orderId(), e);
        }
    }

    public static String rechargeContextJson(RechargeRefundContext context) {
        if (context == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalStateException("充值退款续跑上下文序列化失败 order=" + context.rechargeOrderId(), e);
        }
    }

    public static Optional<OrderRefundContext> parseOrderContext(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            OrderRefundContext context = MAPPER.readValue(json, OrderRefundContext.class);
            return context.version() == CONTEXT_VERSION ? Optional.of(context) : Optional.empty();
        } catch (Exception e) {
            log.error("退款续跑上下文损坏 json={}", json, e);
            return Optional.empty();
        }
    }

    public static Optional<RechargeRefundContext> parseRechargeContext(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            RechargeRefundContext context = MAPPER.readValue(json, RechargeRefundContext.class);
            return context.version() == CONTEXT_VERSION ? Optional.of(context) : Optional.empty();
        } catch (Exception e) {
            log.error("充值退款续跑上下文损坏 json={}", json, e);
            return Optional.empty();
        }
    }

    /** 支付流水已 COMPLETED 后才可调用；只执行本地 FULL/PARTIAL 下游，不碰渠道。 */
    @Transactional
    public boolean resumeOrderRefund(PaymentOperation operation) {
        if (operation == null || !"REFUND".equals(operation.getOperationType())
                || !"COMPLETED".equals(operation.getStatus())) {
            return false;
        }
        Optional<OrderRefundContext> parsed = parseOrderContext(operation.getRefundContextJson());
        if (parsed.isEmpty()) {
            return false;
        }
        OrderRefundContext context = parsed.get();
        if ("DONE".equals(context.downstreamStatus())) {
            return false;
        }
        if (!operation.getOrderId().equals(context.orderId())) {
            throw new IllegalStateException("退款续跑上下文订单不匹配 op=" + operation.getOperationId());
        }
        CabinetOrder order = orderRepository.findById(context.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "退款订单不存在"));
        if ("FULL".equals(context.kind())) {
            waiveRefundService.finalizeWaiveRefund(new SettlementWaiveRefundService.WaiveRefundPrep(
                    order, context.totalCents(), context.reason(), context.restoreInventory()));
        } else if ("PARTIAL".equals(context.kind())) {
            List<OrderRefundRequest.PartialRefundLine> lines = context.refundLines().stream()
                    .map(ContextLine::toRequestLine)
                    .toList();
            partialRefundService.resumeLocalPartialRefund(
                    context.orderId(), lines, context.restoreInventory(), context.totalCents());
        } else {
            throw new IllegalStateException("未知退款续跑类型=" + context.kind());
        }
        markOrderContextDone(context.orderId(), context.resumeToken());
        paymentOperationMapper.finalizeInitiatedRefundTrace(operation.getIdempotencyKey());
        return true;
    }

    /** 分钟级补跑同步 SUCCESS 后调用方崩溃/失败的本地收尾；只处理上下文仍为 PENDING 的完成退款。 */
    public int resumePendingCompletedRefunds(int limit) {
        List<PaymentOperation> candidates = paymentOperationMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getOperationType, "REFUND")
                        .eq(PaymentOperation::getStatus, "COMPLETED")
                        .isNotNull(PaymentOperation::getRefundContextJson)
                        .orderByAsc(PaymentOperation::getCreatedAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
        int resumed = 0;
        for (PaymentOperation candidate : candidates) {
            Optional<OrderRefundContext> context = parseOrderContext(candidate.getRefundContextJson());
            if (context.isEmpty() || "DONE".equals(context.get().downstreamStatus())) {
                continue;
            }
            try {
                if (resumeOrderRefund(candidate)) {
                    resumed++;
                }
            } catch (Exception e) {
                log.error("refund downstream resume failed opId={} orderId={}",
                        candidate.getOperationId(), candidate.getOrderId(), e);
            }
        }
        return resumed;
    }

    /** 同步调用方本地收尾后标记 DONE；CAS 失败说明并发扫描已标记，按幂等成功处理。 */
    @Transactional
    public boolean markOrderContextDone(String orderId, String resumeToken) {
        List<PaymentOperation> candidates = paymentOperationMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getOrderId, orderId)
                        .eq(PaymentOperation::getStatus, "COMPLETED")
                        .eq(PaymentOperation::getOperationType, "REFUND")
                        .isNotNull(PaymentOperation::getRefundContextJson)
                        .orderByDesc(PaymentOperation::getCreatedAt)
                        .last("LIMIT 20"));
        for (PaymentOperation candidate : candidates) {
            Optional<OrderRefundContext> parsed = parseOrderContext(candidate.getRefundContextJson());
            if (parsed.isEmpty() || !resumeToken.equals(parsed.get().resumeToken())
                    || "DONE".equals(parsed.get().downstreamStatus())) {
                continue;
            }
            OrderRefundContext done = withDownstreamStatus(parsed.get(), "DONE");
            if (paymentOperationMapper.updateRefundContextJson(candidate.getOperationId(), "COMPLETED",
                    candidate.getRefundContextJson(), orderContextJson(done)) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 存量 COMPLETED 后迟到 ABNORMAL：只新增 REFUND_FAILED 审计痕迹并发 HIGH 告警，
     * 绝不修改 COMPLETED 资金行、不冲回余额/积分/库存/券/分账。
     */
    @Transactional
    public boolean recordLegacyAbnormalTrace(PaymentOperation operation) {
        if (operation == null || !"COMPLETED".equals(operation.getStatus())) {
            return false;
        }
        String traceKey = operation.getIdempotencyKey() + ":ABNORMAL";
        boolean inserted = paymentOperationMapper.findByIdempotencyKey(traceKey).isEmpty();
        if (inserted) {
            PaymentOperation trace = new PaymentOperation();
            trace.setOperationId(BizIds.nextNumeric());
            trace.setOrderId(operation.getOrderId());
            trace.setUserId(operation.getUserId());
            trace.setOperationType(operation.getOperationType());
            trace.setAmountCents(operation.getAmountCents());
            trace.setChannel(operation.getChannel());
            trace.setStatus("REFUND_FAILED");
            trace.setIdempotencyKey(traceKey);
            trace.setGatewayTradeNo(operation.getGatewayTradeNo());
            trace.setRefundContextJson(operation.getRefundContextJson());
            trace.setReason("渠道退款迟到 ABNORMAL；存量本地已退，仅留痕，不自动冲回");
            paymentOperationMapper.save(trace);
        }
        String nextReason = operation.getReason() == null
                ? "[refund:ABNORMAL]" : operation.getReason().replace("[refund:PROCESSING]", "[refund:ABNORMAL]");
        paymentOperationMapper.markLegacyRefundAbnormal(
                operation.getOperationId(), operation.getReason(), nextReason);
        log.error("legacy completed refund later ABNORMAL opId={} outRefundNo={}",
                operation.getOperationId(), operation.getGatewayTradeNo());
        try {
            opsAlertDispatcher.send("WECHAT_REFUND_ABNORMAL_LEGACY_LOCAL_REVIEW",
                    "【HIGH】微信退款异常（存量本地已退）",
                    "opId=" + operation.getOperationId() + " outRefundNo=" + operation.getGatewayTradeNo()
                            + " 渠道迟到 ABNORMAL；仅落 REFUND_FAILED 痕迹，禁止自动冲回资金，须人工核对券/分账/订单",
                    Map.of("severity", "HIGH", "operationId", operation.getOperationId()));
        } catch (Exception e) {
            log.warn("legacy abnormal alert failed opId={}", operation.getOperationId(), e);
        }
        return inserted;
    }

    public static RechargeRefundContext withRechargeLocalStatus(RechargeRefundContext context, String status) {
        return new RechargeRefundContext(context.version(), context.kind(), context.rechargeOrderId(),
                context.outRefundNo(), context.amountCents(), context.bonusRevokedCents(),
                context.closeAfterSuccess(), context.reason(), status);
    }

    private static OrderRefundContext withDownstreamStatus(OrderRefundContext context, String status) {
        return new OrderRefundContext(context.version(), context.kind(), context.resumeToken(),
                context.orderId(), context.restoreInventory(), context.refundLines(),
                context.totalCents(), context.reason(), status);
    }
}
