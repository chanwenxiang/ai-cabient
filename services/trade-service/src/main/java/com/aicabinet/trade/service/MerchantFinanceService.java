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

    private static final int EXPORT_LIMIT = 5000;
    private static final List<String> PENDING_SPLIT_STATUSES = List.of(
            "PENDING", "ACCRUED", "LEDGER_ONLY", "WECHAT_SUBMITTED", "SUBMITTED");
    private static final List<String> FAILED_SPLIT_STATUSES = List.of("WECHAT_FAILED", "FAILED");

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final CabinetOrderMapper orderRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantMapper merchantRepository;
    private final SettlementService settlementService;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;

    public MerchantFinanceService(PermissionService permissionService,
                                  MerchantScopeService merchantScopeService,
                                  MerchantPortalGuard merchantPortalGuard,
                                  CabinetOrderMapper orderRepository,
                                  OrderRevenueSplitMapper splitRepository,
                                  MerchantMapper merchantRepository,
                                  SettlementService settlementService,
                                  WeChatProfitSharingService profitSharingService,
                                  ProfitSharingProperties profitSharingProperties,
                                  WeChatPayProperties weChatPayProperties) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.orderRepository = orderRepository;
        this.splitRepository = splitRepository;
        this.merchantRepository = merchantRepository;
        this.settlementService = settlementService;
        this.profitSharingService = profitSharingService;
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantOrderSummaryDto> listOrders(Long userId, int page, int size, String deviceId) {
        permissionService.requirePermission(userId, "merchant:orders:list");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CabinetOrder> result = queryOrders(userId, deviceId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toMerchantOrderSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long userId, String orderId) {
        permissionService.requirePermission(userId, "merchant:orders:list");
        merchantPortalGuard.requireAccess(userId);
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(userId, order.getDeviceId());
        return settlementService.getOrderBySession(order.getSessionId());
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CabinetOrder> page = queryOrders(userId, deviceId, pageable);
        StringBuilder sb = new StringBuilder("orderId,sessionId,deviceId,totalAmountCents,status,lineCount,createdAt\n");
        for (CabinetOrder o : page.getContent()) {
            sb.append(csv(o.getOrderId())).append(',')
                    .append(csv(o.getSessionId())).append(',')
                    .append(csv(o.getDeviceId())).append(',')
                    .append(o.getTotalAmountCents()).append(',')
                    .append(csv(o.getStatus())).append(',')
                    .append(o.getLines().size()).append(',')
                    .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public MerchantSettlementOverviewDto getSettlementOverview(Long userId) {
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        if (batchNo == null || batchNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次号不能为空");
        }
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        List<MerchantDailySettlementDto> days = listDailySettlements(userId, fromDate, toDate);
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
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        if (allowed == null || allowed.isEmpty()) {
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
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
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

    private Page<CabinetOrder> queryOrders(Long userId, String deviceId, Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(userId, deviceId);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantScopeService.requireDeviceAccess(userId, deviceId.trim());
            return orderRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId.trim(), pageable);
        }
        if (deviceScope != null) {
            return orderRepository.findByDeviceIdInOrderByCreatedAtDesc(deviceScope, pageable);
        }
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private MerchantOrderSummaryDto toMerchantOrderSummary(CabinetOrder o) {
        return new MerchantOrderSummaryDto(o.getOrderId(), o.getSessionId(), o.getDeviceId(),
                o.getTotalAmountCents(), o.getStatus(), o.getLines().size(), o.getCreatedAt());
    }

    private RevenueSplitDto toSplitDto(OrderRevenueSplit s, String merchantName) {
        return new RevenueSplitDto(
                s.getSplitId(), s.getOrderId(), s.getMerchantId(), merchantName,
                s.getDeviceId(), s.getGrossCents(), s.getPlatformCents(),
                s.getMerchantCents(), s.getStatus(), s.getWechatOutOrderNo(),
                s.getWechatTransactionId(), s.getFailureReason(), s.getCreatedAt(),
                s.getSettlementBatchNo(), s.getSettleAfter(), s.getSettledAt()
        );
    }

    private MerchantDailySettlementDto toDailySettlement(Object[] row) {
        return new MerchantDailySettlementDto(
                String.valueOf(row[0]),
                toLong(row[1]), toLong(row[2]), toLong(row[3]), toLong(row[4]),
                toLong(row[5]), toLong(row[6]), toLong(row[7])
        );
    }

    private MerchantSettlementBatchDto toBatchSettlement(Object[] row, Map<String, String> merchantNames) {
        String batchNo = row[0] != null ? String.valueOf(row[0]) : null;
        String merchantId = row[1] != null ? String.valueOf(row[1]) : null;
        LocalDate settleAfter = toLocalDate(row[2]);
        Instant settledAt = toInstant(row[3]);
        long orderCount = toLong(row[4]);
        long gross = toLong(row[5]);
        long platform = toLong(row[6]);
        long merchant = toLong(row[7]);
        long settled = toLong(row[8]);
        long pending = toLong(row[9]);
        long failed = toLong(row[10]);
        String status = failed > 0 ? "PARTIAL_FAILED" : (pending > 0 ? "PENDING" : "SETTLED");
        return new MerchantSettlementBatchDto(
                batchNo, merchantId, merchantNames.get(merchantId), settleAfter, settledAt,
                orderCount, gross, platform, merchant, settled, pending, failed, status
        );
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
                mock ? "MOCK" : (weChatPayProperties.isConfigured() ? "CONFIGURED" : "MISSING"),
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
        return LocalDate.parse(String.valueOf(value));
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
        return Instant.parse(String.valueOf(value));
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
}
