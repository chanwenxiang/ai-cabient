package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CommissionClawback;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.StaffCommissionDaily;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.CommissionClawbackMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.StaffCommissionDailyMapper;
import com.aicabinet.trade.support.ScheduleZones;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 佣金随退款冲正（CB-040，2026-10-14 用户拍板）。
 *
 * <p>口径（竞品台账 CB-040：有赞/Amazon/AliExpress/TikTok 官方规则压倒性支持
 * 「退款必冲 + 余额不足记负数 + 追溯窗」，同行无公开规格——机制为我们的设计判断；
 * 与老系统有意不同：老系统商户钱包已随退款冲正而佣金不冲，口径矛盾，判定为其缺陷）：
 * <ul>
 *   <li><b>日批重算</b>而非退款事件即时钩子：对追溯窗内的已发佣行按「当前仍 PAID/COMPLETED
 *       的订单」重算佣金基数，差额即应冲回额。这样天然幂等且自洽——退款发生在发佣之前的
 *       订单本来就没被计入发佣基数，重算差额为 0，不会错误冲回。</li>
 *   <li><b>冲回日负数台账行</b>：原日佣行（line/staff_commission_daily）保持不动（留痕，
 *       且其唯一键 (owner,date,device) 不允许同日负行）；累计冲回额落 commission_clawback
 *       （V361，唯一键幂等，每日按差额递增）。</li>
 *   <li><b>余额不足挂负</b>：钱包负向扣减允许扣成负数（已提走的佣金收不回，负数即欠款，
 *       与商户费用欠费同口径）。</li>
 *   <li><b>90 天追溯窗</b>：超过窗期的退款不再冲回佣金（跨行业惯例）。</li>
 *   <li><b>RESTOCK_QTY 模式不冲回</b>：补货件数佣金与销售额无关，退款不影响其基数。
 *       发佣时的模式记在 staff_commission_daily.commission_basis（V361）。</li>
 * </ul>
 *
 * <p>线长冲回额 = 当前费率 × GMV 差额 + 每单固定 × 单数差额（与发佣公式一致；费率事后
 * 变更是已接受的近似，届时差额按新费率计算）。补货员(GMV)冲回额 = 已发佣金 × GMV 差额
 * ÷ 发佣时 GMV 基数（比例冲回，不依赖当前费率，费率变更不产生假冲回）。
 */
@Service
public class CommissionClawbackService {
    private static final Logger log = LoggerFactory.getLogger(CommissionClawbackService.class);
    /** 追溯窗（天）：CB-040 拍板口径。 */
    static final int CLAWBACK_WINDOW_DAYS = 90;
    private static final String ENTRY_TYPE = "COMMISSION_CLAWBACK";

    private final LineCommissionDailyMapper lineCommissionDailyMapper;
    private final StaffCommissionDailyMapper staffCommissionDailyMapper;
    private final CommissionClawbackMapper clawbackMapper;
    private final CabinetOrderMapper orderMapper;
    private final LineManagerMapper managerMapper;
    private final LineWalletService lineWalletService;
    private final StaffWalletService staffWalletService;
    private final TransactionTemplate transactionTemplate;

    public CommissionClawbackService(LineCommissionDailyMapper lineCommissionDailyMapper,
                                     StaffCommissionDailyMapper staffCommissionDailyMapper,
                                     CommissionClawbackMapper clawbackMapper,
                                     CabinetOrderMapper orderMapper,
                                     LineManagerMapper managerMapper,
                                     LineWalletService lineWalletService,
                                     StaffWalletService staffWalletService,
                                     TransactionTemplate transactionTemplate) {
        this.lineCommissionDailyMapper = lineCommissionDailyMapper;
        this.staffCommissionDailyMapper = staffCommissionDailyMapper;
        this.clawbackMapper = clawbackMapper;
        this.orderMapper = orderMapper;
        this.managerMapper = managerMapper;
        this.lineWalletService = lineWalletService;
        this.staffWalletService = staffWalletService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 重算追溯窗内的已发佣行并冲回退款差额，返回冲回事件数。
     * 由 {@link LineCommissionJob} 每日调用（复用其任务锁，免新增调度任务）。
     */
    public int reverseRecentRefunds() {
        LocalDate today = LocalDate.now(ScheduleZones.ZONE);
        LocalDate from = today.minusDays(CLAWBACK_WINDOW_DAYS);
        LocalDate to = today.minusDays(1); // 今日行尚未发佣（00:20 主算昨日）
        int events = 0;
        events += reverseManagerRows(from, to);
        events += reverseStaffRows(from, to);
        return events;
    }

    /** 线长行重算冲回。单行失败不阻断（次日重算自愈）。 */
    private int reverseManagerRows(LocalDate from, LocalDate to) {
        List<LineCommissionDaily> rows = lineCommissionDailyMapper.selectList(
                Wrappers.<LineCommissionDaily>lambdaQuery()
                        .ge(LineCommissionDaily::getBizDate, from)
                        .le(LineCommissionDaily::getBizDate, to)
                        .eq(LineCommissionDaily::getStatus, "POSTED"));
        int events = 0;
        for (LineCommissionDaily row : rows) {
            try {
                if (reverseManagerRow(row)) {
                    events++;
                }
            } catch (Exception e) {
                log.warn("commission clawback manager row failed manager={} device={} date={}",
                        row.getManagerId(), row.getDeviceId(), row.getBizDate(), e);
            }
        }
        return events;
    }

    private boolean reverseManagerRow(LineCommissionDaily row) {
        long gmvNow = paidGmv(row.getDeviceId(), row.getBizDate());
        int countNow = paidOrderCount(row.getDeviceId(), row.getBizDate());
        long gmvDelta = nvl(row.getGmvCents()) - gmvNow;
        int countDelta = nvl(row.getOrderCount()) - countNow;
        if (gmvDelta <= 0 && countDelta <= 0) {
            return false;
        }
        LineManager manager = managerMapper.findById(row.getManagerId()).orElse(null);
        int bps = manager != null && manager.getCommissionRateBps() != null
                ? manager.getCommissionRateBps() : 0;
        int fixed = manager != null && manager.getCommissionFixedCents() != null
                ? manager.getCommissionFixedCents() : 0;
        long gross = gmvDelta * bps / 10_000L + (long) fixed * Math.max(0, countDelta);
        if (gross <= 0) {
            return false;
        }
        long already = findClawedBack(CommissionClawback.OWNER_MANAGER, row.getManagerId(),
                row.getDeviceId(), row.getBizDate());
        final long net = gross - already;
        if (net <= 0) {
            return false;
        }
        final long ownerId = row.getManagerId();
        final String deviceId = row.getDeviceId();
        final LocalDate bizDate = row.getBizDate();
        // 🔴 事务化：累计冲回行递增与钱包负扣同事务（半状态会让「已冲回额」虚增而钱没扣）
        Boolean ok = transactionTemplate.execute(tx -> {
            long after = upsertClawback(CommissionClawback.OWNER_MANAGER, ownerId, deviceId, bizDate,
                    net, gmvDelta, Math.max(0, countDelta));
            return lineWalletService.debitIfAbsentAllowNegative(ownerId, net, ENTRY_TYPE,
                    ENTRY_TYPE, deviceId + "|" + bizDate + "|" + after,
                    "佣金随退款冲回 " + bizDate + " " + deviceId);
        });
        if (Boolean.TRUE.equals(ok)) {
            log.info("commission clawback posted manager={} device={} date={} net={}",
                    ownerId, deviceId, bizDate, net);
        }
        return Boolean.TRUE.equals(ok);
    }

    /** 补货员行重算冲回（仅 GMV 模式；RESTOCK_QTY 与销售无关不冲）。 */
    private int reverseStaffRows(LocalDate from, LocalDate to) {
        List<StaffCommissionDaily> rows = staffCommissionDailyMapper.selectList(
                Wrappers.<StaffCommissionDaily>lambdaQuery()
                        .ge(StaffCommissionDaily::getBizDate, from)
                        .le(StaffCommissionDaily::getBizDate, to)
                        .eq(StaffCommissionDaily::getStatus, "POSTED")
                        .eq(StaffCommissionDaily::getCommissionBasis, "GMV"));
        int events = 0;
        for (StaffCommissionDaily row : rows) {
            try {
                if (reverseStaffRow(row)) {
                    events++;
                }
            } catch (Exception e) {
                log.warn("commission clawback staff row failed staff={} device={} date={}",
                        row.getStaffUserId(), row.getDeviceId(), row.getBizDate(), e);
            }
        }
        return events;
    }

    private boolean reverseStaffRow(StaffCommissionDaily row) {
        long gmvBase = nvl(row.getGmvCents());
        if (gmvBase <= 0) {
            // V361 前的存量行无 GMV 基数，无法比例折算——保守不冲（本功能上线当天起全量有基数）
            return false;
        }
        long gmvNow = paidGmv(row.getDeviceId(), row.getBizDate());
        long gmvDelta = gmvBase - gmvNow;
        if (gmvDelta <= 0) {
            return false;
        }
        long gross = nvl(row.getCommissionCents()) * gmvDelta / gmvBase;
        if (gross <= 0) {
            return false;
        }
        long already = findClawedBack(CommissionClawback.OWNER_STAFF, row.getStaffUserId(),
                row.getDeviceId(), row.getBizDate());
        final long net = gross - already;
        if (net <= 0) {
            return false;
        }
        final long ownerId = row.getStaffUserId();
        final String deviceId = row.getDeviceId();
        final LocalDate bizDate = row.getBizDate();
        Boolean ok = transactionTemplate.execute(tx -> {
            long after = upsertClawback(CommissionClawback.OWNER_STAFF, ownerId, deviceId, bizDate,
                    net, gmvDelta, 0);
            return staffWalletService.debitIfAbsentAllowNegative(ownerId, net, ENTRY_TYPE,
                    ENTRY_TYPE, deviceId + "|" + bizDate + "|" + after,
                    "佣金随退款冲回 " + bizDate + " " + deviceId);
        });
        if (Boolean.TRUE.equals(ok)) {
            log.info("staff commission clawback posted staff={} device={} date={} net={}",
                    ownerId, deviceId, bizDate, net);
        }
        return Boolean.TRUE.equals(ok);
    }

    /** 已累计冲回额（无行返回 0）。 */
    private long findClawedBack(String ownerType, long ownerId, String deviceId, LocalDate bizDate) {
        CommissionClawback row = clawbackMapper.selectOne(Wrappers.<CommissionClawback>lambdaQuery()
                .eq(CommissionClawback::getOwnerType, ownerType)
                .eq(CommissionClawback::getOwnerId, ownerId)
                .eq(CommissionClawback::getDeviceId, deviceId)
                .eq(CommissionClawback::getBizDate, bizDate));
        return row == null ? 0L : nvl(row.getClawbackCents());
    }

    /** 累计冲回行递增（无则建行；唯一键兜底并发，撞键时事务回滚、次日重算自愈）。 */
    private long upsertClawback(String ownerType, long ownerId, String deviceId, LocalDate bizDate,
                                long addCents, long addRefundGmv, int addCount) {
        CommissionClawback row = clawbackMapper.selectOne(Wrappers.<CommissionClawback>lambdaQuery()
                .eq(CommissionClawback::getOwnerType, ownerType)
                .eq(CommissionClawback::getOwnerId, ownerId)
                .eq(CommissionClawback::getDeviceId, deviceId)
                .eq(CommissionClawback::getBizDate, bizDate));
        if (row == null) {
            row = new CommissionClawback();
            row.setOwnerType(ownerType);
            row.setOwnerId(ownerId);
            row.setDeviceId(deviceId);
            row.setBizDate(bizDate);
            row.setRefundGmvCents(addRefundGmv);
            row.setOrderCount(addCount);
            row.setClawbackCents(addCents);
            row.setStatus("POSTED");
            row.setCreatedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            clawbackMapper.insert(row);
            return addCents;
        }
        row.setRefundGmvCents(nvl(row.getRefundGmvCents()) + addRefundGmv);
        row.setOrderCount(nvl(row.getOrderCount()) + addCount);
        row.setClawbackCents(nvl(row.getClawbackCents()) + addCents);
        row.setUpdatedAt(Instant.now());
        clawbackMapper.updateById(row);
        return row.getClawbackCents();
    }

    /**
     * 柜机当日（按订单创建日）佣金基数（审计 T2，2026-10-15）：净额口径
     * （total−refunded，含 PARTIAL_REFUNDED），与发佣 {@link LineCommissionJob} 同源。
     */
    private long paidGmv(String deviceId, LocalDate bizDate) {
        return CommissionBaseCalc.netGmv(ordersOfDay(deviceId, bizDate));
    }

    private int paidOrderCount(String deviceId, LocalDate bizDate) {
        return CommissionBaseCalc.countableCount(ordersOfDay(deviceId, bizDate));
    }

    private List<CabinetOrder> ordersOfDay(String deviceId, LocalDate bizDate) {
        Instant start = bizDate.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant end = bizDate.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        return orderMapper.findByDeviceIdAndCreatedAtBetween(deviceId, start, end);
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
