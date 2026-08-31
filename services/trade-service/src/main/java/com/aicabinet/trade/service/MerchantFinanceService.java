package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商户财务/结算/订单相关的查询与导出。
 */
@Service
public class MerchantFinanceService {
    private static final String MERCHANT_SETTLEMENTS_VIEW = "merchant:settlements:view";


    private static final int EXPORT_LIMIT = 5000;
    private static final List<String> PENDING_SPLIT_STATUSES = List.of(
            "PENDING", "ACCRUED", "LEDGER_ONLY", "WECHAT_SUBMITTED", "SUBMITTED");
    private static final List<String> FAILED_SPLIT_STATUSES = List.of("WECHAT_FAILED", "FAILED");

    private final PermissionService permissionService;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantMapper merchantRepository;
    private final SettlementService settlementService;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final MerchantFinanceService self;

    public MerchantFinanceService(PermissionService permissionService,
                                  MerchantFeaturePackService merchantFeaturePackService,
                                  MerchantPortalGuard merchantPortalGuard,
                                  CabinetOrderMapper orderRepository,
                                  CabinetOrderLineMapper orderLineRepository,
                                  OrderRevenueSplitMapper splitRepository,
                                  MerchantMapper merchantRepository,
                                  SettlementService settlementService,
                                  WeChatProfitSharingService profitSharingService,
                                  ProfitSharingProperties profitSharingProperties,
                                  WeChatPayProperties weChatPayProperties, @Lazy MerchantFinanceService self) {
        this.permissionService = permissionService;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.splitRepository = splitRepository;
        this.merchantRepository = merchantRepository;
        this.settlementService = settlementService;
        this.profitSharingService = profitSharingService;
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantOrderSummaryDto> listOrders(Long userId, MerchantOrderListQuery query) {
        permissionService.requirePermission(userId, "merchant:orders:list");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(query.page(), Math.min(query.size(), 100));
        Page<CabinetOrder> result = queryOrders(userId, query.deviceId(), query.status(),
                query.fromDate(), query.toDate(), query.keyword(), pageable);
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(
                result.getContent().stream().map(CabinetOrder::getOrderId).toList());
        List<String> orderIds = result.getContent().stream().map(CabinetOrder::getOrderId).toList();
        Map<String, List<CabinetOrderLine>> linesByOrder = orderLineRepository.findByOrderIds(orderIds)
                .stream()
                .collect(Collectors.groupingBy(CabinetOrderLine::getOrderId));
        Map<String, String> splitStatusByOrder = splitRepository.findByOrderIdIn(orderIds).stream()
                .filter(s -> s.getOrderId() != null && s.getStatus() != null && !s.getStatus().isBlank())
                .collect(Collectors.toMap(OrderRevenueSplit::getOrderId, OrderRevenueSplit::getStatus, (a, b) -> a));
        return new PageResult<>(
                result.getContent().stream()
                        .map(o -> toMerchantOrderSummary(
                                o,
                                qtyByOrder.getOrDefault(o.getOrderId(), 0),
                                buildLineSummary(linesByOrder.getOrDefault(o.getOrderId(), List.of())),
                                splitStatusByOrder.get(o.getOrderId())))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()
        );
    }

    public record MerchantOrderListQuery(
            int page, int size, String deviceId, String status, String fromDate, String toDate, String keyword) {}

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long userId, String orderId) {
        permissionService.requirePermission(userId, "merchant:orders:list");
        merchantPortalGuard.requireAccess(userId);
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        merchantFeaturePackService.requireDevicePack(userId, order.getDeviceId(), MerchantFeaturePacks.BIZ);
        return settlementService.getOrderBySession(order.getSessionId());
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CabinetOrder> page = queryOrders(userId, deviceId, null, null, null, null, pageable);
        Map<String, Integer> qtyByOrder = orderLineRepository.sumQuantityByOrderIds(
                page.getContent().stream().map(CabinetOrder::getOrderId).toList());
        StringBuilder sb = new StringBuilder("orderId,sessionId,deviceId,totalAmountCents,status,lineCount,createdAt\n");
        for (CabinetOrder o : page.getContent()) {
            sb.append(csv(o.getOrderId())).append(',')
                    .append(csv(o.getSessionId())).append(',')
                    .append(csv(o.getDeviceId())).append(',')
                    .append(o.getTotalAmountCents()).append(',')
                    .append(csv(o.getStatus())).append(',')
                    .append(qtyByOrder.getOrDefault(o.getOrderId(), 0)).append(',')
                    .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public MerchantSettlementOverviewDto getSettlementOverview(Long userId) {
        permissionService.requirePermission(userId, MERCHANT_SETTLEMENTS_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return new MerchantSettlementOverviewDto(0, 0, 0, 0, buildProfitSharingStatus(), List.of());
        }
        long pendingAmount = splitRepository.sumMerchantCentsByMerchantIdInAndStatusIn(
                merchantIds, PENDING_SPLIT_STATUSES);
        long pendingCount = splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);
        Instant startOfMonth = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        long settledMonth = splitRepository.sumSuccessMerchantCentsByMerchantIdInSince(merchantIds, startOfMonth);
        long failedCount = splitRepository.countByMerchantIdInAndStatusIn(merchantIds, FAILED_SPLIT_STATUSES);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> merchantIds.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        List<RevenueSplitDto> recentFailures = splitRepository
                .findTop5ByMerchantIdInAndStatusInOrderByCreatedAtDesc(merchantIds, FAILED_SPLIT_STATUSES)
                .stream()
                .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                .toList();
        return new MerchantSettlementOverviewDto(
                pendingAmount, pendingCount, settledMonth, failedCount,
                buildProfitSharingStatus(), recentFailures);
    }

    @Transactional(readOnly = true)
    public List<MerchantDailySettlementDto> listDailySettlements(Long userId, String fromDate, String toDate) {
        permissionService.requirePermission(userId, MERCHANT_SETTLEMENTS_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        Instant from = parseDateStart(fromDate != null ? fromDate : LocalDate.now().minusDays(30).toString());
        Instant to = parseDateEnd(toDate != null ? toDate : LocalDate.now().toString());
        return splitRepository.aggregateDailyByMerchants(merchantIds, from, to).stream()
                .map(this::toDailySettlement)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantSettlementBatchDto> listSettlementBatches(Long userId, String fromDate, String toDate) {
        permissionService.requirePermission(userId, MERCHANT_SETTLEMENTS_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        Instant from = parseDateStart(fromDate != null ? fromDate : LocalDate.now().minusDays(90).toString());
        Instant to = parseDateEnd(toDate != null ? toDate : LocalDate.now().toString());
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> merchantIds.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        return splitRepository.aggregateBatchByMerchants(merchantIds, from, to).stream()
                .map(row -> toBatchSettlement(row, merchantNames))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevenueSplitDto> getSettlementBatchDetail(Long userId, String batchNo) {
        permissionService.requirePermission(userId, MERCHANT_SETTLEMENTS_VIEW);
        merchantPortalGuard.requireAccess(userId);
        if (batchNo == null || batchNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次号不能为空");
        }
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> merchantIds.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        return splitRepository.findByMerchantIdInAndSettlementBatchNoOrderByCreatedAtDesc(
                        merchantIds, batchNo.trim()).stream()
                .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportSettlementsCsv(Long userId, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:settlements:export");
        merchantPortalGuard.requireAccess(userId);
        List<MerchantDailySettlementDto> days = self.listDailySettlements(userId, fromDate, toDate);
        StringBuilder sb = new StringBuilder();
        sb.append("date,orderCount,grossCents,platformCents,merchantCents,settledCents,pendingCents,failedCount\n");
        for (MerchantDailySettlementDto d : days) {
            sb.append(d.date()).append(',')
                    .append(d.orderCount()).append(',')
                    .append(d.grossCents()).append(',')
                    .append(d.platformCents()).append(',')
                    .append(d.merchantCents()).append(',')
                    .append(d.settledCents()).append(',')
                    .append(d.pendingCents()).append(',')
                    .append(d.failedCount()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public PageResult<RevenueSplitDto> listSplits(Long userId, int page, int size,
                                                  String status, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:splits:list");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (allowed.isEmpty()) {
            return new PageResult<>(List.of(), page, size, 0);
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Instant from = resolveSplitRangeStart(fromDate);
        Instant to = resolveSplitRangeEnd(toDate);
        String normalizedStatus = status != null && !status.isBlank() ? status.trim().toUpperCase() : null;

        Page<OrderRevenueSplit> result = splitRepository.searchByMerchants(
                allowed, normalizedStatus != null ? normalizedStatus : "", from, to, pageable);

        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        return new PageResult<>(
                result.getContent().stream()
                        .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public byte[] exportSplitsCsv(Long userId, String status, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT);
        String normalized = status != null && !status.isBlank() ? status.trim().toUpperCase() : "";
        Page<OrderRevenueSplit> page = splitRepository.searchByMerchants(
                allowed, normalized,
                resolveSplitRangeStart(fromDate), resolveSplitRangeEnd(toDate), pageable);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        StringBuilder sb = new StringBuilder(
                "splitId,orderId,merchantId,merchantName,deviceId,grossCents,platformCents,merchantCents,status,createdAt\n");
        for (OrderRevenueSplit s : page.getContent()) {
            sb.append(csv(s.getSplitId())).append(',')
                    .append(csv(s.getOrderId())).append(',')
                    .append(csv(s.getMerchantId())).append(',')
                    .append(csv(merchantNames.get(s.getMerchantId()))).append(',')
                    .append(csv(s.getDeviceId())).append(',')
                    .append(s.getGrossCents()).append(',')
                    .append(s.getPlatformCents()).append(',')
                    .append(s.getMerchantCents()).append(',')
                    .append(csv(s.getStatus())).append(',')
                    .append(csv(String.valueOf(s.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Page<CabinetOrder> queryOrders(Long userId, String deviceId, String status,
                                           String fromDate, String toDate, String keyword, Pageable pageable) {
        String normalizedDeviceId = (deviceId == null || deviceId.isBlank()) ? null : deviceId.trim();
        if (normalizedDeviceId != null) {
            merchantFeaturePackService.requireDevicePack(userId, normalizedDeviceId, MerchantFeaturePacks.BIZ);
        }
        Collection<String> deviceScope = merchantFeaturePackService.intersectDeviceFilterForPack(
                userId, normalizedDeviceId, MerchantFeaturePacks.BIZ);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        Instant from = (fromDate == null || fromDate.isBlank())
                ? null : parseDateStart(fromDate.trim());
        Instant to = (toDate == null || toDate.isBlank())
                ? null : parseDateEnd(toDate.trim());
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        return orderRepository.findByFiltersOrderByCreatedAtDesc(
                new CabinetOrderMapper.OrderFilterCriteria(
                        normalizedDeviceId, deviceScope, normalizedStatus, null, from, to,
                        null, null, null, null, null, keyword),
                pageable);
    }

    /** lineCount 口径与运营侧一致：商品件数（quantity 合计），非行数。 */
    private MerchantOrderSummaryDto toMerchantOrderSummary(
            CabinetOrder o, int itemQty, String lineSummary, String splitStatus) {
        int coupon = Math.max(0, o.getCouponDiscountCents());
        int member = Math.max(0, o.getMemberDiscountCents());
        int original = o.getOriginalAmountCents() > 0
                ? o.getOriginalAmountCents()
                : o.getTotalAmountCents() + coupon + member;
        return new MerchantOrderSummaryDto(
                o.getOrderId(),
                o.getSessionId(),
                o.getDeviceId(),
                o.getTotalAmountCents(),
                o.getStatus(),
                itemQty,
                o.getCreatedAt(),
                lineSummary,
                resolvePayChannel(o),
                coupon,
                member,
                original,
                o.getRefundedAt(),
                Math.max(0, o.getRefundedCents()),
                o.getDeviceName(),
                o.getMerchantName(),
                o.getPayTradeNo(),
                o.getPaymentOperationId(),
                splitStatus
        );
    }

    /** 与运营/用户端口径一致：余额账本扣款按 BL- 操作号归一为 BALANCE。 */
    private static String resolvePayChannel(CabinetOrder o) {
        String channel = o.getPayChannel();
        if (o.getPaymentOperationId() != null && o.getPaymentOperationId().startsWith("BL-")) {
            channel = "BALANCE";
        }
        return channel == null || channel.isBlank() ? "UNKNOWN" : channel;
    }

    /** 商品摘要，口径与用户端一致：名称 x数量、等N件。 */
    private static String buildLineSummary(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String preview = lines.stream()
                .limit(2)
                .map(l -> {
                    String name = l.getSkuName() + " x" + l.getQuantity();
                    if (l.getSlotId() != null && !l.getSlotId().isBlank()) {
                        name += " ·货道" + l.getSlotId().trim();
                    }
                    if (l.getBatchNo() != null && !l.getBatchNo().isBlank()) {
                        name += " @" + l.getBatchNo().trim();
                    }
                    return name;
                })
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (lines.size() > 2) {
            return preview + " 等" + lines.size() + "件";
        }
        return preview;
    }

    private RevenueSplitDto toSplitDto(OrderRevenueSplit s, String merchantName) {
        String deviceName = null;
        if (s.getOrderId() != null && !s.getOrderId().isBlank()) {
            deviceName = orderRepository.findById(s.getOrderId())
                    .map(CabinetOrder::getDeviceName)
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(null);
        }
        return new RevenueSplitDto(
                s.getSplitId(), s.getOrderId(), s.getMerchantId(), merchantName,
                s.getDeviceId(), s.getGrossCents(), s.getPlatformCents(),
                s.getMerchantCents(), s.getStatus(), s.getWechatOutOrderNo(),
                s.getWechatTransactionId(), s.getFailureReason(), s.getCreatedAt(),
                s.getSettlementBatchNo(), s.getSettleAfter(), s.getSettledAt(),
                deviceName
        );
    }

    private MerchantDailySettlementDto toDailySettlement(Object[] row) {
        return new MerchantDailySettlementDto(
                String.valueOf(at(row, 0)),
                toLong(at(row, 1)), toLong(at(row, 2)), toLong(at(row, 3)), toLong(at(row, 4)),
                toLong(at(row, 5)), toLong(at(row, 6)), toLong(at(row, 7))
        );
    }

    private MerchantSettlementBatchDto toBatchSettlement(Object[] row, Map<String, String> merchantNames) {
        String batchNo = at(row, 0) != null ? String.valueOf(at(row, 0)) : null;
        String merchantId = at(row, 1) != null ? String.valueOf(at(row, 1)) : null;
        LocalDate settleAfter = toLocalDate(at(row, 2));
        Instant settledAt = toInstant(at(row, 3));
        long orderCount = toLong(at(row, 4));
        long gross = toLong(at(row, 5));
        long platform = toLong(at(row, 6));
        long merchant = toLong(at(row, 7));
        long settled = toLong(at(row, 8));
        long pending = toLong(at(row, 9));
        long failed = toLong(at(row, 10));
        String status = settlementBatchStatus(failed, pending, "PENDING");
        return new MerchantSettlementBatchDto(
                batchNo, merchantId, merchantNames.get(merchantId), settleAfter, settledAt,
                orderCount, gross, platform, merchant, settled, pending, failed, status
        );
    }

    private static Object at(Object[] row, int index) {
        return row != null && index >= 0 && index < row.length ? row[index] : null;
    }

    private ProfitSharingStatusDto buildProfitSharingStatus() {
        boolean enabled = profitSharingProperties.enabled();
        boolean apiReady = profitSharingService.isApiReady();
        boolean mock = profitSharingService.isMockMode();
        String note;
        if (!enabled) {
            note = "平台分账功能未启用，当前为记账模式";
        } else if (mock) {
            note = "平台分账联调 Mock 已启用";
        } else if (!weChatPayProperties.isConfigured()) {
            note = "微信支付未配置，分账将延迟到账";
        } else if (!apiReady) {
            note = "分账 API 未就绪，请联系平台运营";
        } else {
            note = "分账 API 已就绪，待分账款项将由平台定期提交";
        }
        return new ProfitSharingStatusDto(
                enabled, apiReady, profitSharingProperties.retryEnabled(),
                profitSharingProperties.retryBatchSize(),
                wechatConfigLabel(mock, weChatPayProperties.isConfigured()),
                note
        );
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (value instanceof Instant i) {
            return LocalDate.ofInstant(i, ZoneId.systemDefault());
        }
        String raw = String.valueOf(value).trim();
        if (raw.length() >= 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-') {
            return LocalDate.parse(raw.substring(0, 10));
        }
        return null;
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Number n) {
            long epoch = n.longValue();
            // tolerate seconds vs millis
            return Instant.ofEpochMilli(epoch < 100_000_000_000L ? epoch * 1000L : epoch);
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || raw.matches("^\\d{1,2}$")) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.matches("^[=+\\-@].*")) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static Instant parseDateStart(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant parseDateEnd(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date.trim()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant resolveSplitRangeStart(String fromDate) {
        Instant from = parseDateStart(fromDate);
        if (from != null) {
            return from;
        }
        return LocalDate.now(ZoneId.systemDefault()).minusYears(10)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant resolveSplitRangeEnd(String toDate) {
        Instant to = parseDateEnd(toDate);
        if (to != null) {
            return to;
        }
        return LocalDate.now(ZoneId.systemDefault()).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static String wechatConfigLabel(boolean mock, boolean configured) {
        if (mock) {
            return "MOCK";
        }
        return configured ? "CONFIGURED" : "MISSING";
    }

    private static String settlementBatchStatus(long failed, long pending, String pendingStatus) {
        if (failed > 0) {
            return "PARTIAL_FAILED";
        }
        if (pending > 0) {
            return pendingStatus;
        }
        return "SETTLED";
    }
}
