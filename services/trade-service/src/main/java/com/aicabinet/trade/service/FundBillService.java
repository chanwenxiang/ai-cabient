package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FinanceMarginLockDto;
import com.aicabinet.common.dto.FundDailyBillDto;
import com.aicabinet.common.dto.FundLedgerEntryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.FinanceMarginDailyLock;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.FinanceMarginDailyLockMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FundBillService {
    private static final String PERM_OPS_FINANCE_VIEW = "ops:finance:view";
    private static final String PERM_OPS_FUND_LIST = "ops:fund:list";


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 通道费按实付约 0.6% 估算展示（微信/支付宝常见费率量级） */
    private static final double CHANNEL_FEE_RATE = 0.006;

    private final OrderRevenueSplitMapper splitMapper;
    private final MerchantMapper merchantMapper;
    private final FinanceMarginDailyLockMapper marginLockMapper;
    private final CabinetOrderMapper orderMapper;
    private final CabinetOrderLineMapper lineMapper;
    private final InventoryWriteOffMapper writeOffMapper;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final FundBillService self;

    public FundBillService(OrderRevenueSplitMapper splitMapper,
                           MerchantMapper merchantMapper,
                           FinanceMarginDailyLockMapper marginLockMapper,
                           CabinetOrderMapper orderMapper,
                           CabinetOrderLineMapper lineMapper,
                           InventoryWriteOffMapper writeOffMapper,
                           MerchantScopeService merchantScopeService,
                           PermissionService permissionService,
                           DistributedLockService distributedLockService, @Lazy FundBillService self) {
        this.splitMapper = splitMapper;
        this.merchantMapper = merchantMapper;
        this.marginLockMapper = marginLockMapper;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.writeOffMapper = writeOffMapper;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<FundDailyBillDto> listDailyBills(Long operatorId, String fromDate, String toDate) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_FUND_LIST, PERM_OPS_FINANCE_VIEW);
        LocalDate from = parseDate(fromDate, LocalDate.now(ZONE).minusDays(30));
        LocalDate to = parseDate(toDate, LocalDate.now(ZONE));
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();

        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
        var q = Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, start)
                .lt(OrderRevenueSplit::getCreatedAt, end)
                .orderByDesc(OrderRevenueSplit::getCreatedAt);
        if (deviceIds != null) {
            if (deviceIds.isEmpty()) {
                return List.of();
            }
            q.in(OrderRevenueSplit::getDeviceId, deviceIds);
        }
        List<OrderRevenueSplit> splits = splitMapper.selectList(q);
        Map<String, String> merchantNames = merchantMapper.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
        Set<LocalDate> locked = marginLockMapper.findByBizDateBetween(from, to).stream()
                .map(FinanceMarginDailyLock::getBizDate)
                .collect(Collectors.toSet());

        record Key(String date, String merchantId) {}
        Map<Key, Agg> aggs = new HashMap<>();
        for (OrderRevenueSplit s : splits) {
            String status = s.getStatus() == null ? "" : s.getStatus().trim().toUpperCase();
            if ("VOIDED".equals(status) || "REVERSED".equals(status)) {
                continue;
            }
            LocalDate d = LocalDate.ofInstant(s.getCreatedAt(), ZONE);
            Key key = new Key(d.toString(), s.getMerchantId());
            Agg a = aggs.computeIfAbsent(key, k -> new Agg());
            a.orderCount++;
            a.gross += s.getGrossCents();
            a.platform += s.getPlatformCents();
            if (isMerchantCreditedStatus(status)) {
                a.credited += s.getMerchantCents();
            } else {
                a.pending += s.getMerchantCents();
            }
        }

        List<FundDailyBillDto> out = new ArrayList<>();
        for (Map.Entry<Key, Agg> e : aggs.entrySet()) {
            Agg a = e.getValue();
            long channelFee = Math.round(a.gross * CHANNEL_FEE_RATE);
            LocalDate biz = LocalDate.parse(e.getKey().date());
            out.add(new FundDailyBillDto(
                    e.getKey().date(),
                    e.getKey().merchantId(),
                    merchantNames.get(e.getKey().merchantId()),
                    a.gross,
                    a.platform,
                    channelFee,
                    a.credited,
                    a.pending,
                    a.orderCount,
                    locked.contains(biz) || biz.isBefore(LocalDate.now(ZONE))
            ));
        }
        out.sort(Comparator.comparing(FundDailyBillDto::bizDate).reversed()
                .thenComparing(FundDailyBillDto::merchantId));
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<FundLedgerEntryDto> listLedger(Long operatorId, String fromDate, String toDate,
                                                     String financialType, String direction,
                                                     int page, int size) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_FUND_LIST, PERM_OPS_FINANCE_VIEW);
        LocalDate from = parseDate(fromDate, LocalDate.now(ZONE).minusDays(7));
        LocalDate to = parseDate(toDate, LocalDate.now(ZONE));
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);

        var q = Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, start)
                .lt(OrderRevenueSplit::getCreatedAt, end)
                .orderByDesc(OrderRevenueSplit::getCreatedAt);
        if (deviceIds != null) {
            if (deviceIds.isEmpty()) {
                return new PageResult<>(List.of(), page, size, 0);
            }
            q.in(OrderRevenueSplit::getDeviceId, deviceIds);
        }
        // 拉取窗口内分账再展开为账务行（演示规模可接受）
        List<OrderRevenueSplit> splits = splitMapper.selectList(q);
        Map<String, String> merchantNames = merchantMapper.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));

        List<FundLedgerEntryDto> rows = new ArrayList<>();
        for (OrderRevenueSplit s : splits) {
            rows.add(entry(s, "ORDER_PAYMENT", "IN", s.getGrossCents(), merchantNames));
            if (s.getPlatformCents() > 0) {
                rows.add(entry(s, "PLATFORM_FEE", "OUT", s.getPlatformCents(), merchantNames));
            }
            long channel = Math.round(s.getGrossCents() * CHANNEL_FEE_RATE);
            if (channel > 0) {
                rows.add(entry(s, "CHANNEL_FEE", "OUT", channel, merchantNames));
            }
            rows.add(entry(s, "MERCHANT_CREDIT", "IN", s.getMerchantCents(), merchantNames));
        }
        if (financialType != null && !financialType.isBlank()) {
            String ft = financialType.trim().toUpperCase();
            rows = rows.stream().filter(r -> ft.equals(r.financialType())).collect(Collectors.toList());
        }
        if (direction != null && !direction.isBlank()) {
            String dir = direction.trim().toUpperCase();
            rows = rows.stream().filter(r -> dir.equals(r.direction())).collect(Collectors.toList());
        }
        int fromIdx = Math.min(page * size, rows.size());
        int toIdx = Math.min(fromIdx + size, rows.size());
        return new PageResult<>(rows.subList(fromIdx, toIdx), page, size, rows.size());
    }

    @Transactional(readOnly = true)
    public byte[] exportDailyBillsCsv(Long operatorId, String fromDate, String toDate) {
        permissionService.requireAnyPermission(operatorId, "ops:fund:export", PERM_OPS_FUND_LIST, PERM_OPS_FINANCE_VIEW);
        StringBuilder sb = new StringBuilder(
                "bizDate,merchantId,merchantName,orderPaidCents,platformFeeCents,channelFeeCents,creditedCents,pendingCents,orderCount,solidified\n");
        for (FundDailyBillDto d : self.listDailyBills(operatorId, fromDate, toDate)) {
            sb.append(d.bizDate()).append(',')
                    .append(csv(d.merchantId())).append(',')
                    .append(csv(d.merchantName())).append(',')
                    .append(d.orderPaidCents()).append(',')
                    .append(d.platformFeeCents()).append(',')
                    .append(d.channelFeeCents()).append(',')
                    .append(d.creditedCents()).append(',')
                    .append(d.pendingCents()).append(',')
                    .append(d.orderCount()).append(',')
                    .append(d.solidified()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public FinanceMarginLockDto solidifyMargin(Long operatorId, LocalDate bizDate) {
        if (operatorId != null) {
            permissionService.requireAnyPermission(operatorId, PERM_OPS_FINANCE_VIEW, PERM_OPS_FUND_LIST);
        }
        LocalDate day = bizDate != null ? bizDate : LocalDate.now(ZONE).minusDays(1);
        if (!day.isBefore(LocalDate.now(ZONE))) {
            day = LocalDate.now(ZONE).minusDays(1);
        }
        final LocalDate targetDay = day;
        return runWithMarginSolidifyLock(targetDay, () -> doSolidifyMargin(operatorId, targetDay));
    }

    private FinanceMarginLockDto doSolidifyMargin(Long operatorId, LocalDate day) {
        Instant start = day.atStartOfDay(ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<String> deviceIds = operatorId == null ? null : merchantScopeService.allowedDeviceIds(operatorId);
        long revenue = deviceIds == null
                ? orderMapper.sumTotalAmountBetween(start, end)
                : deviceIds.isEmpty() ? 0 : orderMapper.sumTotalAmountByDeviceIdInBetween(deviceIds, start, end);
        long cogs = deviceIds == null
                ? lineMapper.sumCogsBetween(start, end)
                : deviceIds.isEmpty() ? 0 : lineMapper.sumCogsByDeviceIdsBetween(deviceIds, start, end);
        long writeOff = deviceIds == null
                ? writeOffMapper.sumCostCentsBetween(start, end)
                : deviceIds.isEmpty() ? 0 : writeOffMapper.sumCostCentsByDeviceIdsBetween(deviceIds, start, end);
        long orderCount = countOrdersBetween(deviceIds, start, end);

        FinanceMarginDailyLock lock = marginLockMapper.findById(day).orElseGet(FinanceMarginDailyLock::new);
        lock.setBizDate(day);
        lock.setRevenueCents(revenue);
        lock.setCogsCents(cogs);
        lock.setMarginCents(revenue - cogs);
        lock.setWriteOffCents(writeOff);
        lock.setOrderCount(orderCount);
        lock.setLockedAt(Instant.now());
        lock.setLockedBy(operatorId);
        marginLockMapper.save(lock);
        return toLockDto(lock, true);
    }

    @Transactional(readOnly = true)
    public List<FinanceMarginLockDto> listMarginLocks(Long operatorId, String fromDate, String toDate) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_FINANCE_VIEW, PERM_OPS_FUND_LIST);
        LocalDate from = parseDate(fromDate, LocalDate.now(ZONE).minusDays(30));
        LocalDate to = parseDate(toDate, LocalDate.now(ZONE));
        Map<LocalDate, FinanceMarginDailyLock> locked = marginLockMapper.findByBizDateBetween(from, to).stream()
                .collect(Collectors.toMap(FinanceMarginDailyLock::getBizDate, x -> x, (a, b) -> a));
        List<FinanceMarginLockDto> out = new ArrayList<>();
        for (LocalDate d = to; !d.isBefore(from); d = d.minusDays(1)) {
            FinanceMarginDailyLock lock = locked.get(d);
            if (lock != null) {
                out.add(toLockDto(lock, true));
            } else if (d.equals(LocalDate.now(ZONE))) {
                Instant start = d.atStartOfDay(ZONE).toInstant();
                Instant end = d.plusDays(1).atStartOfDay(ZONE).toInstant();
                Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
                long revenue = deviceIds == null
                        ? orderMapper.sumTotalAmountBetween(start, end)
                        : deviceIds.isEmpty() ? 0 : orderMapper.sumTotalAmountByDeviceIdInBetween(deviceIds, start, end);
                long cogs = deviceIds == null
                        ? lineMapper.sumCogsBetween(start, end)
                        : deviceIds.isEmpty() ? 0 : lineMapper.sumCogsByDeviceIdsBetween(deviceIds, start, end);
                long writeOff = deviceIds == null
                        ? writeOffMapper.sumCostCentsBetween(start, end)
                        : deviceIds.isEmpty() ? 0 : writeOffMapper.sumCostCentsByDeviceIdsBetween(deviceIds, start, end);
                long orderCount = countOrdersBetween(deviceIds, start, end);
                out.add(new FinanceMarginLockDto(d.toString(), revenue, cogs, revenue - cogs, writeOff, orderCount,
                        null, null, false));
            } else {
                out.add(new FinanceMarginLockDto(d.toString(), 0, 0, 0, 0, 0, null, null, false));
            }
        }
        return out;
    }

    /** 财务报表：历史日优先读固化快照 */
    @Transactional(readOnly = true)
    public FinanceMarginLockDto marginForDay(Long operatorId, LocalDate day) {
        return marginLockMapper.findById(day)
                .map(l -> toLockDto(l, true))
                .orElse(null);
    }

    private FundLedgerEntryDto entry(OrderRevenueSplit s, String type, String dir, long amount,
                                     Map<String, String> merchantNames) {
        return new FundLedgerEntryDto(
                s.getSplitId() + ":" + type,
                type,
                dir,
                amount,
                s.getMerchantId(),
                merchantNames.get(s.getMerchantId()),
                s.getDeviceId(),
                s.getOrderId(),
                s.getWechatTransactionId(),
                "WECHAT",
                s.getCreatedAt()
        );
    }

    private static FinanceMarginLockDto toLockDto(FinanceMarginDailyLock lock, boolean locked) {
        return new FinanceMarginLockDto(
                lock.getBizDate().toString(),
                lock.getRevenueCents(),
                lock.getCogsCents(),
                lock.getMarginCents(),
                lock.getWriteOffCents(),
                lock.getOrderCount(),
                lock.getLockedAt(),
                lock.getLockedBy(),
                locked
        );
    }

    private long countOrdersBetween(Set<String> deviceIds, Instant start, Instant end) {
        var q = Wrappers.<com.aicabinet.trade.domain.CabinetOrder>lambdaQuery()
                .ge(com.aicabinet.trade.domain.CabinetOrder::getCreatedAt, start)
                .lt(com.aicabinet.trade.domain.CabinetOrder::getCreatedAt, end);
        if (deviceIds != null) {
            if (deviceIds.isEmpty()) {
                return 0;
            }
            q.in(com.aicabinet.trade.domain.CabinetOrder::getDeviceId, deviceIds);
        }
        Long c = orderMapper.selectCount(q);
        return c == null ? 0 : c;
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(raw.trim());
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        return s.contains(",") ? "\"" + s + "\"" : s;
    }

    private static class Agg {
        long orderCount;
        long gross;
        long platform;
        long credited;
        long pending;
    }

    static String marginSolidifyLockKey(LocalDate bizDate) {
        return "fund:margin:solidify:" + bizDate;
    }

    static boolean isMerchantCreditedStatus(String status) {
        return "SUCCESS".equals(status) || "SETTLED".equals(status) || "LEDGER_ONLY".equals(status);
    }

    private FinanceMarginLockDto runWithMarginSolidifyLock(LocalDate day,
                                                           java.util.function.Supplier<FinanceMarginLockDto> action) {
        if (!distributedLockService.tryLock(marginSolidifyLockKey(day), 120, 5)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "毛利快照固化中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(marginSolidifyLockKey(day));
        }
    }
}
