package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FinanceMarginLockDto;
import com.aicabinet.common.dto.FundDailyBillDto;
import com.aicabinet.common.dto.FundLedgerEntryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.FinanceMarginDailyLock;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.PaymentReconciliation;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.FinanceMarginDailyLockMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.domain.DeviceInfo;
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
    /**
     * V309：通道费费率（万分比）走运营台配置，不再硬编码。
     *
     * <p>🔴 <b>为什么用 bps（整数）而不是 double 比例</b>：运营手填 {@code 0.006} 极易写成
     * {@code 0.06}（放大 10 倍）或 {@code .6}（放大 100 倍），而<b>这个数字直接乘在商户结算金额上</b>，
     * 填错一行会让平台侧通道费虚高十倍并可能亏穿。整数万分比（60 = 0.6%）配合上界钳制后，
     * 最坏情况也被限在 {@link #CHANNEL_FEE_BPS_MAX} 内。
     *
     * <p>⚠️ <b>口径提醒</b>：这是<b>按实付金额估算的展示值</b>，不是渠道实际结算出来的费率。
     * 真实通道费应以渠道账单为准（与 {@code PayoutReconciliationService} 同源问题：
     * 本地口径 ≠ 与渠道对平）。把它做成可配是为了让运营能按<b>实际签约费率</b>校正展示值，
     * 而不是让运营只能看着一个写死的 0.6% 猜。
     */
    private static final int CHANNEL_FEE_BPS_DEFAULT = 60;
    /** 费率上界 1000 bps = 10%。超过即视为误填并回落默认，避免一个 0 头失误算成通道费倒挂。 */
    private static final int CHANNEL_FEE_BPS_MAX = 1000;


    private final OrderRevenueSplitMapper splitMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final MerchantMapper merchantMapper;
    private final FinanceMarginDailyLockMapper marginLockMapper;
    /** CB-020③：读对账主表取日实结通道费（channel_fee_cents），实结优先、估算兜底。 */
    private final PaymentReconciliationMapper reconMapper;
    private final CabinetOrderMapper orderMapper;
    private final CabinetOrderLineMapper lineMapper;
    private final InventoryWriteOffMapper writeOffMapper;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final DistributedLockService distributedLockService;
    private final SystemConfigService systemConfigService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final FundBillService self;

    public FundBillService(OrderRevenueSplitMapper splitMapper,
                           DeviceInfoMapper deviceInfoMapper,
                           MerchantMapper merchantMapper,
                           FinanceMarginDailyLockMapper marginLockMapper,
                           PaymentReconciliationMapper reconMapper,
                           CabinetOrderMapper orderMapper,
                           CabinetOrderLineMapper lineMapper,
                           InventoryWriteOffMapper writeOffMapper,
                           MerchantScopeService merchantScopeService,
                           PermissionService permissionService,
                           DistributedLockService distributedLockService,
                           SystemConfigService systemConfigService,
                           @Lazy FundBillService self) {
        this.splitMapper = splitMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.merchantMapper = merchantMapper;
        this.marginLockMapper = marginLockMapper;
        this.reconMapper = reconMapper;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.writeOffMapper = writeOffMapper;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.distributedLockService = distributedLockService;
        this.systemConfigService = systemConfigService;
        this.self = self;
    }

    /**
     * V309：读运营台配置的通道费率（bps）。
     *
     * <p>容错口径与 {@code WithdrawPolicyResolver.pick} 一致：配置缺失/非数字/超界都<b>回落默认 60bps</b>，
     * 而不是抛异常 —— 资金看板因为一个手填错的值整体打不开，比费率估错更糟。
     *
     * <p>CB-020③：static 化供 {@code ReconciliationService} 费率差异告警复用同一配置源，
     * 避免「看板估算用一个 bps、对账告警用另一个 bps」的口径分裂。
     */
    static int resolveChannelFeeBps(SystemConfigService systemConfigService) {
        if (systemConfigService == null) {
            return CHANNEL_FEE_BPS_DEFAULT;
        }
        // 用 SystemConfigService 的常量而非字面量 ⇒ 门禁 R4 能静态扫到，漏登记会红
        int bps = systemConfigService.getInt(SystemConfigService.FUND_CHANNEL_FEE_BPS, CHANNEL_FEE_BPS_DEFAULT);
        if (bps < 0 || bps > CHANNEL_FEE_BPS_MAX) {
            return CHANNEL_FEE_BPS_DEFAULT;
        }
        return bps;
    }

    private int channelFeeBps() {
        return resolveChannelFeeBps(systemConfigService);
    }

    /** 按 bps 折算整数分：{@code gross * bps / 10000}，用 long 中间值避免 int 溢出。 */
    private long estimateChannelFeeCents(long grossCents, int bps) {
        if (grossCents <= 0 || bps <= 0) {
            return 0L;
        }
        return Math.round((double) grossCents * bps / 10_000.0d);
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

        // CB-020③：日实结通道费 = 当日全部渠道对账主表 channel_fee_cents 非 null 合计。
        // 实结是「日×渠道」粒度而账单行是「日×商户」——按当日各商户 gross 占比分摊，尾差挂当日最大商户行；
        // 当日无任何实结（未对账/历史行/Mock 通道）⇒ 整日回落 bps 估算（SOURCE_ESTIMATED），前端可辨来源。
        Map<String, Long> dailyActualFee = new HashMap<>();
        if (reconMapper != null) {
            for (PaymentReconciliation r : reconMapper.findByReconDateBetweenOrderByReconDateDesc(from, to)) {
                if (r != null && r.getChannelFeeCents() != null && r.getReconDate() != null) {
                    dailyActualFee.merge(r.getReconDate().toString(), r.getChannelFeeCents(), Long::sum);
                }
            }
        }
        Map<String, Long> dailyGross = new HashMap<>();
        aggs.forEach((k, v) -> dailyGross.merge(k.date(), v.gross, Long::sum));
        // 实结存在但当日无有效分账（全 VOIDED）⇒ 无分摊载体，丢弃避免除零
        dailyActualFee.keySet().removeIf(d -> {
            Long g = dailyGross.get(d);
            return g == null || g <= 0;
        });

        List<FundDailyBillDto> out = new ArrayList<>();
        for (Map.Entry<Key, Agg> e : aggs.entrySet()) {
            Agg a = e.getValue();
            LocalDate biz = LocalDate.parse(e.getKey().date());
            Long dayActual = dailyActualFee.get(e.getKey().date());
            long channelFee;
            String source;
            if (dayActual != null) {
                source = FundDailyBillDto.SOURCE_ACTUAL;
                long dayGross = dailyGross.get(e.getKey().date());
                channelFee = Math.round((double) dayActual * a.gross / dayGross);
            } else {
                source = FundDailyBillDto.SOURCE_ESTIMATED;
                channelFee = estimateChannelFeeCents(a.gross, channelFeeBps());
            }
            out.add(new FundDailyBillDto(
                    e.getKey().date(),
                    e.getKey().merchantId(),
                    merchantNames.get(e.getKey().merchantId()),
                    a.gross,
                    a.platform,
                    channelFee,
                    source,
                    a.credited,
                    a.pending,
                    a.orderCount,
                    locked.contains(biz) || biz.isBefore(LocalDate.now(ZONE))
            ));
        }
        distributeActualFeeRemainder(out, dailyActualFee);
        out.sort(Comparator.comparing(FundDailyBillDto::bizDate).reversed()
                .thenComparing(FundDailyBillDto::merchantId));
        return out;
    }

    /**
     * CB-020③：把比例分摊的四舍五入尾差归到当日 gross 最大商户行，保证
     * {@code Σmerchant.channelFeeCents == 当日实结合计}（对不平的账比不精确的分摊更糟）。
     */
    private static void distributeActualFeeRemainder(List<FundDailyBillDto> out, Map<String, Long> dailyActualFee) {
        if (dailyActualFee.isEmpty()) {
            return;
        }
        Map<String, List<Integer>> byDate = new HashMap<>();
        for (int i = 0; i < out.size(); i++) {
            byDate.computeIfAbsent(out.get(i).bizDate(), k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Integer>> en : byDate.entrySet()) {
            Long dayActual = dailyActualFee.get(en.getKey());
            if (dayActual == null || en.getValue().size() < 2) {
                continue;
            }
            long spread = 0;
            int biggestIdx = -1;
            for (int idx : en.getValue()) {
                spread += out.get(idx).channelFeeCents();
                if (biggestIdx < 0 || out.get(idx).orderPaidCents() > out.get(biggestIdx).orderPaidCents()) {
                    biggestIdx = idx;
                }
            }
            long remainder = dayActual - spread;
            if (remainder != 0 && biggestIdx >= 0) {
                FundDailyBillDto row = out.get(biggestIdx);
                out.set(biggestIdx, new FundDailyBillDto(
                        row.bizDate(), row.merchantId(), row.merchantName(),
                        row.orderPaidCents(), row.platformFeeCents(),
                        row.channelFeeCents() + remainder, row.channelFeeSource(),
                        row.creditedCents(), row.pendingCents(), row.orderCount(), row.solidified()
                ));
            }
        }
    }

    @Transactional(readOnly = true)
    public PageResult<FundDailyBillDto> listDailyBillsPage(Long operatorId, String fromDate, String toDate,
                                                            String keyword, int page, int size) {
        List<FundDailyBillDto> all = filterDailyBills(self.listDailyBills(operatorId, fromDate, toDate), keyword);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        int from = p * s;
        if (from >= all.size()) {
            return new PageResult<>(List.of(), p, s, all.size());
        }
        int to = Math.min(from + s, all.size());
        return new PageResult<>(all.subList(from, to), p, s, all.size());
    }

    static List<FundDailyBillDto> filterDailyBills(List<FundDailyBillDto> rows, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return rows;
        }
        String kw = keyword.trim().toLowerCase();
        return rows.stream().filter(r -> matchesDailyBillKeyword(r, kw)).toList();
    }

    static boolean matchesDailyBillKeyword(FundDailyBillDto row, String kw) {
        return containsIgnoreCase(row.merchantId(), kw)
                || containsIgnoreCase(row.merchantName(), kw)
                || containsIgnoreCase(row.bizDate(), kw);
    }

    static List<FundLedgerEntryDto> filterLedgerRows(List<FundLedgerEntryDto> rows, String keyword,
                                                     Map<String, String> deviceNames) {
        if (keyword == null || keyword.isBlank()) {
            return rows;
        }
        String kw = keyword.trim().toLowerCase();
        return rows.stream().filter(r -> matchesLedgerKeyword(r, kw, deviceNames)).toList();
    }

    static boolean matchesLedgerKeyword(FundLedgerEntryDto row, String kw, Map<String, String> deviceNames) {
        return containsIgnoreCase(row.entryId(), kw)
                || containsIgnoreCase(row.orderId(), kw)
                || containsIgnoreCase(row.deviceId(), kw)
                || containsIgnoreCase(row.merchantId(), kw)
                || containsIgnoreCase(row.merchantName(), kw)
                || containsIgnoreCase(deviceNames.get(row.deviceId()), kw);
    }

    private static boolean containsIgnoreCase(String value, String kw) {
        return value != null && value.toLowerCase().contains(kw);
    }

    @Transactional(readOnly = true)
    public PageResult<FundLedgerEntryDto> listLedger(Long operatorId, String fromDate, String toDate,
                                                     String financialType, String direction, String keyword,
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
            // CB-020③：逐单明细行维持估算——实结费在账单上虽有逐单粒度（账单行 merchantOrderNo ↔ 订单），
            // 但需按 wechatTransactionId↔platformTradeNo 精确关联账单行，成本高；日账单（listDailyBills）
            // 已实结优先，FundBillChannelFeeRateTest 钉住本行为估算口径（契约），逐单实结留后续增量。
            long channel = estimateChannelFeeCents(s.getGrossCents(), channelFeeBps());
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
        Map<String, String> deviceNames = keyword == null || keyword.isBlank()
                ? Map.of()
                : deviceInfoMapper.findAllOrderByDeviceIdAsc().stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName, (a, b) -> a));
        rows = filterLedgerRows(rows, keyword, deviceNames);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        int fromIdx = Math.min(p * s, rows.size());
        int toIdx = Math.min(fromIdx + s, rows.size());
        return new PageResult<>(rows.subList(fromIdx, toIdx), p, s, rows.size());
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
        long revenue = sumScoped(deviceIds, () -> orderMapper.sumTotalAmountBetween(start, end),
                () -> orderMapper.sumTotalAmountByDeviceIdInBetween(deviceIds, start, end));
        long cogs = sumScoped(deviceIds, () -> lineMapper.sumCogsBetween(start, end),
                () -> lineMapper.sumCogsByDeviceIdsBetween(deviceIds, start, end));
        long writeOff = sumScoped(deviceIds, () -> writeOffMapper.sumCostCentsBetween(start, end),
                () -> writeOffMapper.sumCostCentsByDeviceIdsBetween(deviceIds, start, end));
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
                out.add(liveMarginForToday(operatorId, d));
            } else {
                out.add(new FinanceMarginLockDto(d.toString(), 0, 0, 0, 0, 0, null, null, false));
            }
        }
        return out;
    }

    private FinanceMarginLockDto liveMarginForToday(Long operatorId, LocalDate day) {
        Instant start = day.atStartOfDay(ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
        long revenue = sumMarginRevenue(deviceIds, start, end);
        long cogs = sumMarginCogs(deviceIds, start, end);
        long writeOff = sumMarginWriteOff(deviceIds, start, end);
        long orderCount = countOrdersBetween(deviceIds, start, end);
        return new FinanceMarginLockDto(day.toString(), revenue, cogs, revenue - cogs, writeOff, orderCount,
                null, null, false);
    }

    private long sumMarginRevenue(Set<String> deviceIds, Instant start, Instant end) {
        if (deviceIds == null) {
            return orderMapper.sumTotalAmountBetween(start, end);
        }
        return deviceIds.isEmpty() ? 0 : orderMapper.sumTotalAmountByDeviceIdInBetween(deviceIds, start, end);
    }

    private long sumMarginCogs(Set<String> deviceIds, Instant start, Instant end) {
        if (deviceIds == null) {
            return lineMapper.sumCogsBetween(start, end);
        }
        return deviceIds.isEmpty() ? 0 : lineMapper.sumCogsByDeviceIdsBetween(deviceIds, start, end);
    }

    private long sumMarginWriteOff(Set<String> deviceIds, Instant start, Instant end) {
        if (deviceIds == null) {
            return writeOffMapper.sumCostCentsBetween(start, end);
        }
        return deviceIds.isEmpty() ? 0 : writeOffMapper.sumCostCentsByDeviceIdsBetween(deviceIds, start, end);
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
        // S3/审计 P1-7：统一走 CsvCells——原实现仅防逗号，补引号/换行包裹与公式注入中和
        return com.aicabinet.trade.support.CsvCells.escape(v);
    }

    private static long sumScoped(Set<String> deviceIds,
                                  java.util.function.LongSupplier allScope,
                                  java.util.function.LongSupplier scoped) {
        if (deviceIds == null) {
            return allScope.getAsLong();
        }
        if (deviceIds.isEmpty()) {
            return 0;
        }
        return scoped.getAsLong();
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
