package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class LineCommissionJob {
    private static final String LINE_COMMISSION = "line-commission";
    /** 除昨天外回扫的天数窗口：抢锁失败/停机漏算的设备-日在窗口内自愈补算（H19）。 */
    private static final int BACKFILL_DAYS = 7;


    private static final Logger log = LoggerFactory.getLogger(LineCommissionJob.class);
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "COMPLETED");

    private final LineManagerMapper managerMapper;
    private final LineDeviceMapper deviceMapper;
    private final CabinetOrderMapper orderMapper;
    private final com.aicabinet.trade.mapper.ReplenishmentTaskMapper replenishmentTaskMapper;
    private final com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper replenishmentTaskLineMapper;
    private final com.aicabinet.trade.mapper.LineRouteMapper lineRouteMapper;
    private final com.aicabinet.trade.mapper.LineRouteMemberMapper lineRouteMemberMapper;
    private final com.aicabinet.trade.mapper.StaffCommissionDailyMapper staffCommissionDailyMapper;
    private final StaffWalletService staffWalletService;
    private final CommissionClawbackService clawbackService;
    private final LineCommissionDailyMapper commissionDailyMapper;
    private final LineWalletService lineWalletService;
    private final DistributedLockService distributedLockService;
    private final ScheduledTaskService taskService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public LineCommissionJob(LineManagerMapper managerMapper,
                             LineDeviceMapper deviceMapper,
                             CabinetOrderMapper orderMapper,
                             LineCommissionDailyMapper commissionDailyMapper,
                             LineWalletService lineWalletService,
                             DistributedLockService distributedLockService,
                             ScheduledTaskService taskService,
                             org.springframework.transaction.support.TransactionTemplate transactionTemplate,
            com.aicabinet.trade.mapper.ReplenishmentTaskMapper replenishmentTaskMapper,
            com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper replenishmentTaskLineMapper,
            com.aicabinet.trade.mapper.LineRouteMapper lineRouteMapper,
            com.aicabinet.trade.mapper.LineRouteMemberMapper lineRouteMemberMapper,
            com.aicabinet.trade.mapper.StaffCommissionDailyMapper staffCommissionDailyMapper,
            StaffWalletService staffWalletService,
            CommissionClawbackService clawbackService) {
        this.managerMapper = managerMapper;
        this.deviceMapper = deviceMapper;
        this.orderMapper = orderMapper;
        this.commissionDailyMapper = commissionDailyMapper;
        this.lineWalletService = lineWalletService;
        this.distributedLockService = distributedLockService;
        this.replenishmentTaskMapper = replenishmentTaskMapper;
        this.replenishmentTaskLineMapper = replenishmentTaskLineMapper;
        this.lineRouteMapper = lineRouteMapper;
        this.lineRouteMemberMapper = lineRouteMemberMapper;
        this.staffCommissionDailyMapper = staffCommissionDailyMapper;
        this.staffWalletService = staffWalletService;
        this.clawbackService = clawbackService;
        this.taskService = taskService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void postDailyCommission() {
        long taskStart = System.nanoTime();
        if (!taskService.tryBegin(LINE_COMMISSION, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无线长佣金入账";
        try {
            LocalDate today = LocalDate.now(ScheduleZones.ZONE);
            // 主算昨天 + 回扫最近 7 天：单设备-日抢锁失败仅跳过当天，下次运行自愈（H19）
            LinkedHashSet<LocalDate> bizDates = new LinkedHashSet<>();
            for (int i = 1; i <= BACKFILL_DAYS; i++) {
                bizDates.add(today.minusDays(i));
            }
            List<LineDevice> bindings = deviceMapper.findByStatus(LineManagerService.STATUS_ACTIVE);
            int posted = 0;
            for (LineDevice binding : bindings) {
                LineManager manager = managerMapper.findById(binding.getManagerId()).orElse(null);
                if (manager == null || !LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
                    continue;
                }
                for (LocalDate bizDate : bizDates) {
                    // 无锁预查重，避免已入账设备-日反复抢锁
                    if (commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(
                            manager.getManagerId(), bizDate, binding.getDeviceId()).isPresent()) {
                        continue;
                    }
                    if (tryPostCommissionForBinding(manager, binding, bizDate)) {
                        posted++;
                    }
                }
            }
            int staffPosted = postStaffRestockCommissions(bizDates);
            // CB-040 佣金随退款冲正：发佣后重算 90 天窗内已发佣行的退款差额（复用本任务锁，
            // 免新增调度任务；单行失败不阻断，次日重算自愈）
            int clawbacks = clawbackService.reverseRecentRefunds();
            summary = posted <= 0
                    ? "本次无线长佣金入账（含回扫 " + BACKFILL_DAYS + " 天）"
                    : "入账线长佣金 " + posted + " 条（含回扫 " + BACKFILL_DAYS + " 天）";
            if (clawbacks > 0) {
                summary += "，冲回退款佣金 " + clawbacks + " 笔";
            }
            if (posted > 0) {
                log.info("Line commission posted for {} device-day rows (backfill window={}d)",
                        posted, BACKFILL_DAYS);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(LINE_COMMISSION, "FAILED", e.getMessage(), taskStart);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(LINE_COMMISSION, "SUCCESS", summary, taskStart);
            }
        }
    }

    static String lineCommissionDailyLockKey(long managerId, String deviceId, LocalDate bizDate) {
        return "line-commission:daily:" + managerId + ":" + deviceId + ":" + bizDate;
    }

    private boolean tryPostCommissionForBinding(LineManager manager, LineDevice binding, LocalDate bizDate) {
        Instant start = bizDate.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant end = bizDate.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        String lockKey = lineCommissionDailyLockKey(
                manager.getManagerId(), binding.getDeviceId(), bizDate);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            log.warn("line commission lock busy manager={} device={} date={}",
                    manager.getManagerId(), binding.getDeviceId(), bizDate);
            return false;
        }
        try {
            // 审计批次4：事务在**锁内、按设备-日独立开启**——原类级 @Transactional 会让整个
            // job（N 设备 × 7 天 × 每行多次行锁）包在单个长事务里，任一失败全回滚且长时间占
            // 连接。拆为每设备-日一个短事务：单条失败仅该日跳过（下次回扫自愈）。
            Boolean posted = transactionTemplate.execute(tx ->
                    doPostCommissionTx(manager, binding, bizDate, start, end));
            return Boolean.TRUE.equals(posted);
        } catch (Exception e) {
            log.warn("line commission device-day failed manager={} device={} date={}",
                    manager.getManagerId(), binding.getDeviceId(), bizDate, e);
            return false;
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    /** 单设备-日佣金入账（在 transactionTemplate 的独立事务内执行）。 */
    private boolean doPostCommissionTx(LineManager manager, LineDevice binding, LocalDate bizDate,
                                       java.time.Instant start, java.time.Instant end) {
        if (commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(
                    manager.getManagerId(), bizDate, binding.getDeviceId()).isPresent()) {
                return false;
            }
            List<CabinetOrder> orders = orderMapper
                    .findByDeviceIdAndCreatedAtBetween(binding.getDeviceId(), start, end)
                    .stream()
                    .filter(o -> o.getStatus() != null && PAID_STATUSES.contains(o.getStatus().toUpperCase()))
                    .toList();
            if (orders.isEmpty()) {
                return false;
            }
            long gmv = orders.stream().mapToLong(CabinetOrder::getTotalAmountCents).sum();
            int rateBps = manager.getCommissionRateBps() == null ? 0 : manager.getCommissionRateBps();
            int fixed = manager.getCommissionFixedCents() == null ? 0 : manager.getCommissionFixedCents();
            long commission = gmv * rateBps / 10_000L + (long) fixed * orders.size();
            if (commission <= 0) {
                return false;
            }
            LineCommissionDaily row = new LineCommissionDaily();
            row.setManagerId(manager.getManagerId());
            row.setBizDate(bizDate);
            row.setDeviceId(binding.getDeviceId());
            row.setOrderCount(orders.size());
            row.setGmvCents(gmv);
            row.setCommissionCents(commission);
            row.setStatus("POSTED");
            row.setCreatedAt(Instant.now());
            commissionDailyMapper.insert(row);
            String refId = bizDate + "|" + binding.getDeviceId();
            lineWalletService.credit(manager.getManagerId(), commission, "COMMISSION",
                    "COMMISSION_DAILY", refId, "线长日佣金 " + bizDate + " " + binding.getDeviceId());
            return true;
    }


    /**
     * 补货员日佣金台账（V358，线下结算口径：只记台账不结钱）：
     * 按线路成员（role=STAFF）的提成配置，统计其各回扫日实际补货留痕（补货任务
     * assignee=本人、COMPLETED）件数 → staff_commission_daily（唯一键幂等）。
     * 一台柜多人补货天然支持（按 assignee 归属到当天实际补货人）；线长与补货员同一人时
     * 两条账并行不叠加：线长销售提成走 line_commission_daily（钱包），补货佣金走本表（台账）。
     */
    int postStaffRestockCommissions(LinkedHashSet<LocalDate> bizDates) {
        List<com.aicabinet.trade.domain.LineRouteMember> staffs = lineRouteMemberMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.aicabinet.trade.domain.LineRouteMember>lambdaQuery()
                        .eq(com.aicabinet.trade.domain.LineRouteMember::getRole, "STAFF")
                        .eq(com.aicabinet.trade.domain.LineRouteMember::getStatus, "ACTIVE"));
        int posted = 0;
        for (com.aicabinet.trade.domain.LineRouteMember staff : staffs) {
            for (LocalDate bizDate : bizDates) {
                List<com.aicabinet.trade.domain.ReplenishmentTask> tasks = replenishmentTaskMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<com.aicabinet.trade.domain.ReplenishmentTask>lambdaQuery()
                                .eq(com.aicabinet.trade.domain.ReplenishmentTask::getAssigneeUserId, staff.getUserId())
                                .eq(com.aicabinet.trade.domain.ReplenishmentTask::getStatus, "COMPLETED")
                                .ge(com.aicabinet.trade.domain.ReplenishmentTask::getCompletedAt,
                                        bizDate.atStartOfDay(ScheduleZones.ZONE).toInstant())
                                .lt(com.aicabinet.trade.domain.ReplenishmentTask::getCompletedAt,
                                        bizDate.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant()));
                if (tasks.isEmpty()) {
                    continue;
                }
                List<Long> taskIds = tasks.stream().map(com.aicabinet.trade.domain.ReplenishmentTask::getTaskId).toList();
                var lines = replenishmentTaskLineMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<com.aicabinet.trade.domain.ReplenishmentTaskLine>lambdaQuery()
                                .in(com.aicabinet.trade.domain.ReplenishmentTaskLine::getTaskId, taskIds));
                java.util.Map<String, Integer> qtyByDevice = new java.util.LinkedHashMap<>();
                for (var ln : lines) {
                    var task = tasks.stream().filter(t -> t.getTaskId().equals(ln.getTaskId())).findFirst().orElse(null);
                    if (task == null || task.getDeviceId() == null) {
                        continue;
                    }
                    qtyByDevice.merge(task.getDeviceId(), ln.getQuantity(), Integer::sum);
                }
                for (var e : qtyByDevice.entrySet()) {
                    // 🔴 三元组查重（审计 🔴6）：唯一键 (staff,date,device)——二元组预查重会让
                    // 「部分设备已插入」的日期整段跳过，剩余设备佣金永久补不上
                    Long rowExists = staffCommissionDailyMapper.selectCount(
                            com.baomidou.mybatisplus.core.toolkit.Wrappers
                                    .<com.aicabinet.trade.domain.StaffCommissionDaily>lambdaQuery()
                                    .eq(com.aicabinet.trade.domain.StaffCommissionDaily::getStaffUserId, staff.getUserId())
                                    .eq(com.aicabinet.trade.domain.StaffCommissionDaily::getBizDate, bizDate)
                                    .eq(com.aicabinet.trade.domain.StaffCommissionDaily::getDeviceId, e.getKey()));
                    if (rowExists != null && rowExists > 0) {
                        continue;
                    }
                    long commission = 0L;
                    long gmvBase = 0L;
                    if ("RESTOCK_QTY".equalsIgnoreCase(staff.getCommissionBasis())) {
                        long perUnit = staff.getCommissionPerUnitCents() == null ? 0L : staff.getCommissionPerUnitCents();
                        commission = (long) e.getValue() * perUnit;
                    } else {
                        // GMV 模式（对齐老系统 MachineCommissionLink.type=0 按销售额）：
                        // 补货员负责的柜机昨日已支付销售额 × rateBps（万分比）
                        int bps = staff.getCommissionRateBps() == null ? 0 : staff.getCommissionRateBps();
                        if (bps > 0) {
                            long gmv = orderMapper
                                    .findByDeviceIdAndCreatedAtBetween(e.getKey(),
                                            bizDate.atStartOfDay(ScheduleZones.ZONE).toInstant(),
                                            bizDate.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant())
                                    .stream()
                                    .filter(o -> o.getStatus() != null
                                            && PAID_STATUSES.contains(o.getStatus().toUpperCase()))
                                    .mapToLong(CabinetOrder::getTotalAmountCents)
                                    .sum();
                            gmvBase = gmv;
                            commission = gmv * bps / 10_000L;
                        }
                    }
                    if (commission <= 0) {
                        continue;
                    }
                    // lambda 捕获要求 effectively final（commission 在 GMV 分支被再赋值）
                    final long commissionCents = commission;
                    final long gmvBaseCents = gmvBase;
                    // V361：记录发佣模式与 GMV 基数（佣金随退款冲正 CB-040 的重算依据）
                    final String basisUsed = "RESTOCK_QTY".equalsIgnoreCase(staff.getCommissionBasis())
                            ? "RESTOCK_QTY" : "GMV";
                    // 🔴 事务化（审计 🔴6）：台账 insert 与钱包 credit 同事务——崩溃不会出现
                    // 「台账已落、钱包没进钱」的半状态（与线长侧 doPostCommissionTx 同款模式）
                    Boolean ok = transactionTemplate.execute(tx -> {
                        com.aicabinet.trade.domain.StaffCommissionDaily row = new com.aicabinet.trade.domain.StaffCommissionDaily();
                        row.setStaffUserId(staff.getUserId());
                        row.setRouteId(staff.getRouteId());
                        row.setBizDate(bizDate);
                        row.setDeviceId(e.getKey());
                        row.setRestockQty(e.getValue());
                        row.setCommissionCents(commissionCents);
                        row.setGmvCents(gmvBaseCents);
                        row.setCommissionBasis(basisUsed);
                        row.setStatus("POSTED");
                        row.setCreatedAt(java.time.Instant.now());
                        staffCommissionDailyMapper.insert(row);
                        // 资金视角：佣金入补货员钱包（V359，对齐老系统 CommissionAccount 可提现）
                        return staffWalletService.creditIfAbsent(staff.getUserId(), commissionCents, "COMMISSION",
                                "STAFF_COMMISSION_DAILY", bizDate + "|" + e.getKey(),
                                "补货员佣金 " + bizDate + " " + e.getKey());
                    });
                    if (Boolean.TRUE.equals(ok)) {
                        posted++;
                    }
                }
            }
        }
        return posted;
    }
}
