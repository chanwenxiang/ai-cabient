package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SupplierReconciliationDto;
import com.aicabinet.trade.domain.Supplier;
import com.aicabinet.trade.domain.SupplierPayable;
import com.aicabinet.trade.mapper.SupplierMapper;
import com.aicabinet.trade.mapper.SupplierPayableEntryMapper;
import com.aicabinet.trade.mapper.SupplierPayableMapper;
import com.aicabinet.trade.mapper.SupplierPaymentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 供应商月度对账单（V326，E3 缺口 #8；竞品口径见 {@code docs/COMPETITOR_BENCHMARK.md} CB-016）。
 *
 * <p><b>行业公式（勤策 ERP 原文）</b>：期末余额 = 期初余额 + 本期应付 − 本期付款；
 * 本系统展开为：{@code closing = opening + received − returned − paid}。
 *
 * <p><b>数据基础是流水，不是快照</b>：{@code supplier_payable.amount_cents} 是原地修改的
 * 快照（收货累加/退货冲减都改同一行），只有 {@code supplier_payable_entry}（V326）
 * 与 {@code supplier_payment}（V159）两类 append-only 流水才能支撑期初口径。
 *
 * <p>🔴 <b>诚实边界</b>：流水系统启用（V326 执行时刻）之前的月份没有流水数据，
 * 查那些月份返回的期初/发生/期末都是 0——这是「没数据」，不是「余额为 0」。
 * 视图层应提示用户对账单最早可用月份 = V326 上线月份。
 *
 * <p>🔴 <b>月边界口径</b>：用 UTC（与仓内其他统计一致，如 {@code WriteOffClaimLedgerService}）。
 * 跨月边界的 8 小时时区差异对月度对账影响极小，且本方与供应商对账时双方同看同一张单。
 */
@Service
public class SupplierReconciliationService {

    private static final String PERM_OPS_PROCUREMENT_LIST = "ops:procurement:list";

    private final PermissionService permissionService;
    private final SupplierPayableEntryMapper entryMapper;
    private final SupplierPaymentMapper paymentMapper;
    private final SupplierPayableMapper payableMapper;
    private final SupplierMapper supplierMapper;

    public SupplierReconciliationService(PermissionService permissionService,
                                         SupplierPayableEntryMapper entryMapper,
                                         SupplierPaymentMapper paymentMapper,
                                         SupplierPayableMapper payableMapper,
                                         SupplierMapper supplierMapper) {
        this.permissionService = permissionService;
        this.entryMapper = entryMapper;
        this.paymentMapper = paymentMapper;
        this.payableMapper = payableMapper;
        this.supplierMapper = supplierMapper;
    }

    /**
     * 生成指定供应商、指定月份的对账单。
     *
     * @param month {@code yyyy-MM}（如 {@code 2026-10}）
     */
    @Transactional(readOnly = true)
    public SupplierReconciliationDto monthlyReconciliation(Long operatorId, String supplierId, String month) {
        permissionService.requirePermission(operatorId, PERM_OPS_PROCUREMENT_LIST);
        YearMonth ym = parseMonth(month);
        Instant monthStart = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        long opening = signedEntrySum(supplierId, monthStart);
        long received = entrySum(supplierId, "RECEIVE", monthStart, monthEnd);
        long returned = entrySum(supplierId, "RETURN", monthStart, monthEnd);
        long paid = paymentSum(supplierId, monthStart, monthEnd);
        long closing = closingOf(opening, received, returned, paid);

        // 交叉验证（铁律 19：两套实现互查）：流水全量推算余额 vs 主表当前余额。
        // 相等 ⇒ 流水与主表无脱节；不等 ⇒ 有人绕过 Service 改库/有未登记事件，对账单不可信。
        long ledgerBalance = signedEntrySum(supplierId, null) - paymentSum(supplierId, null, null);
        long mainBalance = payableMapper.selectList(Wrappers.<SupplierPayable>lambdaQuery()
                        .eq(SupplierPayable::getSupplierId, supplierId)).stream()
                .mapToLong(p -> p.getAmountCents() - p.getPaidAmountCents())
                .sum();

        return new SupplierReconciliationDto(
                supplierId,
                supplierName(supplierId),
                ym.toString(),
                opening,
                received,
                returned,
                paid,
                closing,
                ledgerBalance,
                mainBalance,
                ledgerBalance == mainBalance);
    }

    /** 期初口径：月首前（before 为 null = 全部）净发生额 = Σ(RECEIVE+OPENING) − Σ(RETURN)。 */
    private long signedEntrySum(String supplierId, Instant before) {
        // OPENING 与 RECEIVE 同向为正，RETURN 为负；一条 SQL 带回，避免三次往返。
        List<Map<String, Object>> rows = entryMapper.selectMaps(Wrappers.<com.aicabinet.trade.domain.SupplierPayableEntry>query()
                .select("COALESCE(SUM(CASE WHEN entry_type = 'RETURN' THEN -amount_cents ELSE amount_cents END), 0) AS net_cents")
                .eq("supplier_id", supplierId)
                .lt(before != null, "created_at", before));
        return rows.isEmpty() || rows.get(0) == null ? 0L : asLong(rows.get(0).get("net_cents"));
    }

    /** 本期发生：指定类型在 [from, to) 内的合计。 */
    private long entrySum(String supplierId, String entryType, Instant from, Instant to) {
        List<Map<String, Object>> rows = entryMapper.selectMaps(Wrappers.<com.aicabinet.trade.domain.SupplierPayableEntry>query()
                .select("COALESCE(SUM(amount_cents), 0) AS sum_cents")
                .eq("supplier_id", supplierId)
                .eq("entry_type", entryType)
                .ge("created_at", from)
                .lt("created_at", to));
        return rows.isEmpty() || rows.get(0) == null ? 0L : asLong(rows.get(0).get("sum_cents"));
    }

    private long paymentSum(String supplierId, Instant from, Instant to) {
        List<Map<String, Object>> rows = paymentMapper.selectMaps(Wrappers.<com.aicabinet.trade.domain.SupplierPayment>query()
                .select("COALESCE(SUM(amount_cents), 0) AS sum_cents")
                .eq("supplier_id", supplierId)
                .ge(from != null, "created_at", from)
                .lt(to != null, "created_at", to));
        return rows.isEmpty() || rows.get(0) == null ? 0L : asLong(rows.get(0).get("sum_cents"));
    }

    /** SUM 在 PG 里对 BIGINT 回 BIGINT，驱动可能给 Long/BigDecimal/Integer——统一收口。 */
    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private String supplierName(String supplierId) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        return supplier == null ? supplierId : supplier.getSupplierName();
    }

    /** 行业公式（CB-016）：期末余额 = 期初余额 + 本期新增应付 − 本期退货冲减 − 本期付款。纯函数便于测试。 */
    static long closingOf(long opening, long received, long returned, long paid) {
        return opening + received - returned - paid;
    }

    /** 月份参数解析；非法格式抛 400（与仓内其他参数校验一致，不静默当 0 处理）。 */
    static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month is required (yyyy-MM)");
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be yyyy-MM");
        }
    }
}
