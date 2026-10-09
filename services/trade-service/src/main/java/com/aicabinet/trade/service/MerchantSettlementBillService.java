package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MerchantSettlementBill;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.MerchantSettlementBillMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商户月度结算单（CB-020 ②，V330）。
 *
 * <p>背景：商户端既有「结算批次」是 order_revenue_split 按 settlement_batch_no 的
 * 聚合视图，而 batchNo 逐行独立发号——批次实际逐单一号，无对账单形态。本服务把
 * 每个商户的每个账期月聚合成一张<b>物理结算单</b>（{@code merchant_settlement_bill}），
 * 对齐友宝「每月正式结算」节奏。</p>
 *
 * <p>口径（与商户端既有视图可对平，差异处均在 javadoc 注明）：</p>
 * <ul>
 *   <li>账期月 = split.created_at 的 Asia/Shanghai 日历月（与既有日结算/批次视图
 *       的 created_at 窗口一致）。</li>
 *   <li>排除退款冲正单 VOIDED/REVERSED（旧聚合视图未剔除，属口径瑕疵，不延续）。</li>
 *   <li>settled = status='SUCCESS'；pending = 其余有效状态；failedCount 只数
 *       WECHAT_FAILED/FAILED（与 selectAggregateBatchByMerchants 的 c8/c9/c10 同义）。</li>
 *   <li>重建即真相：幂等整月覆盖；已 CONFIRMED 行只刷新金额不动状态；本月已无
 *       有效 split 的陈旧行删除。</li>
 * </ul>
 */
@Service
public class MerchantSettlementBillService {

    private static final Logger log = LoggerFactory.getLogger(MerchantSettlementBillService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BILL_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantSettlementBillMapper billRepository;
    private final DistributedLockService distributedLockService;

    public MerchantSettlementBillService(OrderRevenueSplitMapper splitRepository,
                                         MerchantSettlementBillMapper billRepository,
                                         DistributedLockService distributedLockService) {
        this.splitRepository = splitRepository;
        this.billRepository = billRepository;
        this.distributedLockService = distributedLockService;
    }

    static String settlementBillLockKey(YearMonth month) {
        return "settlement-bill:rebuild:" + month;
    }

    /** 结算单号确定性生成：SB+yyyyMM-商户号（同月同商户重建不换号，唯一索引兜底）。 */
    static String buildBillNo(YearMonth month, String merchantId) {
        return "SB" + month.format(BILL_MONTH) + "-" + merchantId;
    }

    /** 退款冲正单不计入结算单：VOIDED=全额退款冲正、REVERSED=分账回退冲正。 */
    static boolean isReversed(String status) {
        return "VOIDED".equalsIgnoreCase(status) || "REVERSED".equalsIgnoreCase(status);
    }

    /**
     * 重建某账期月的全部商户结算单（幂等，整月覆盖）。
     *
     * @return 写入行数（insert + update，不含删除的陈旧行）
     */
    @Transactional
    public int rebuildMonth(YearMonth month) {
        if (!distributedLockService.tryLock(settlementBillLockKey(month), 120, 5)) {
            log.warn("settlement bill rebuild lock busy month={}", month);
            return 0;
        }
        try {
            return doRebuildMonth(month);
        } finally {
            distributedLockService.unlock(settlementBillLockKey(month));
        }
    }

    private int doRebuildMonth(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        List<OrderRevenueSplit> splits = splitRepository.selectList(Wrappers.<OrderRevenueSplit>lambdaQuery()
                .ge(OrderRevenueSplit::getCreatedAt, from)
                .lt(OrderRevenueSplit::getCreatedAt, to)
                .isNotNull(OrderRevenueSplit::getSettlementBatchNo));
        List<MerchantSettlementBill> rows = buildRows(month, splits, Instant.now());

        int written = 0;
        for (MerchantSettlementBill row : rows) {
            MerchantSettlementBill existing =
                    billRepository.findByMerchantAndPeriod(row.getMerchantId(), row.getPeriodMonth());
            if (existing == null) {
                billRepository.insert(row);
            } else {
                // 快照刷新只动金额与统计时间；status/confirmedAt 保留（CONFIRMED 不被重置回 PENDING）。
                // 🔴 联合主键无 @TableId，禁用 updateById，按 wrapper 更新。
                existing.setOrderCount(row.getOrderCount());
                existing.setGrossCents(row.getGrossCents());
                existing.setPlatformCents(row.getPlatformCents());
                existing.setMerchantCents(row.getMerchantCents());
                existing.setSettledCents(row.getSettledCents());
                existing.setPendingCents(row.getPendingCents());
                existing.setFailedCount(row.getFailedCount());
                existing.setComputedAt(row.getComputedAt());
                billRepository.update(existing, Wrappers.<MerchantSettlementBill>lambdaQuery()
                        .eq(MerchantSettlementBill::getMerchantId, existing.getMerchantId())
                        .eq(MerchantSettlementBill::getPeriodMonth, existing.getPeriodMonth()));
            }
            written++;
        }

        deleteStaleRows(month, rows);
        log.info("settlement bill rebuilt month={} merchants={} window=[{}, {})", month, written, from, to);
        return written;
    }

    /** 本月已无有效 split 的商户结算单删除（重建即真相，避免退款冲正后账单残留虚高金额）。 */
    private void deleteStaleRows(YearMonth month, List<MerchantSettlementBill> currentRows) {
        Set<String> currentMerchantIds = currentRows.stream()
                .map(MerchantSettlementBill::getMerchantId)
                .collect(Collectors.toSet());
        List<MerchantSettlementBill> persisted = billRepository.selectList(Wrappers.<MerchantSettlementBill>lambdaQuery()
                .eq(MerchantSettlementBill::getPeriodMonth, month.atDay(1)));
        for (MerchantSettlementBill old : persisted) {
            if (old.getMerchantId() != null && !currentMerchantIds.contains(old.getMerchantId())) {
                billRepository.delete(Wrappers.<MerchantSettlementBill>lambdaQuery()
                        .eq(MerchantSettlementBill::getMerchantId, old.getMerchantId())
                        .eq(MerchantSettlementBill::getPeriodMonth, month.atDay(1)));
                log.info("settlement bill stale row removed month={} merchant={}", month, old.getMerchantId());
            }
        }
    }

    /**
     * 纯函数：把账期月窗口内的 split 按商户聚合为结算单行（未持久化）。
     * 口径见类注释；包级可见供纯逻辑单测直接钉住。
     */
    static List<MerchantSettlementBill> buildRows(YearMonth month,
                                                  List<OrderRevenueSplit> splits,
                                                  Instant computedAt) {
        // 0=count, 1=gross, 2=platform, 3=merchant, 4=settled, 5=pending, 6=failed
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (OrderRevenueSplit s : splits) {
            String merchantId = s.getMerchantId();
            if (merchantId == null || merchantId.isBlank() || isReversed(s.getStatus())) {
                continue;
            }
            long[] a = agg.computeIfAbsent(merchantId, k -> new long[7]);
            a[0]++;
            a[1] += s.getGrossCents();
            a[2] += s.getPlatformCents();
            a[3] += s.getMerchantCents();
            if ("SUCCESS".equalsIgnoreCase(s.getStatus())) {
                a[4] += s.getMerchantCents();
            } else {
                a[5] += s.getMerchantCents();
            }
            if ("WECHAT_FAILED".equalsIgnoreCase(s.getStatus()) || "FAILED".equalsIgnoreCase(s.getStatus())) {
                a[6]++;
            }
        }
        List<MerchantSettlementBill> rows = new ArrayList<>(agg.size());
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            MerchantSettlementBill row = new MerchantSettlementBill();
            row.setMerchantId(e.getKey());
            row.setPeriodMonth(month.atDay(1));
            row.setBillNo(buildBillNo(month, e.getKey()));
            row.setOrderCount((int) e.getValue()[0]);
            row.setGrossCents(e.getValue()[1]);
            row.setPlatformCents(e.getValue()[2]);
            row.setMerchantCents(e.getValue()[3]);
            row.setSettledCents(e.getValue()[4]);
            row.setPendingCents(e.getValue()[5]);
            row.setFailedCount((int) e.getValue()[6]);
            row.setComputedAt(computedAt);
            rows.add(row);
        }
        return rows;
    }
}
