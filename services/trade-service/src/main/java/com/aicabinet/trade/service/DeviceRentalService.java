package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceRentalBill;
import com.aicabinet.trade.domain.DeviceRentalDeposit;
import com.aicabinet.trade.domain.DeviceRentalDepositEvent;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceRentalBillMapper;
import com.aicabinet.trade.mapper.DeviceRentalDepositEventMapper;
import com.aicabinet.trade.mapper.DeviceRentalDepositMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备租赁闭环（CB-034）：coopMode=RENTAL 的柜机按月出应收租金账单、押金收据化、欠租自动锁柜。
 *
 * <p><b>边界（读 CB-034 结论 1/2/3/4/6）</b>：</p>
 * <ol>
 *   <li><b>押金是负债不是收入</b>：不走平台资金池台账；收缴/返还均为线下动作，本服务只做收据化登记
 *       （登记→扣款抵扣→结算返还），每笔扣款必须填依据并留审计。</li>
 *   <li><b>租金是应收不是应付</b>：与 DeviceDataFeeBill（平台付流量费）方向相反；收款人工标记，
 *       不自动扣款（不碰资金动作）。</li>
 *   <li><b>欠租锁柜复用 DeviceSalesLockService</b>：逾期 UNPAID 账单 → 锁机；结清 → 解锁。
 *       解锁只针对「因欠租而锁」的柜机（salesLockReason 以 RENTAL_LOCK_PREFIX 开头），
 *       不碰其它原因的锁（维修/风控等各有自己的解锁路径）。</li>
 *   <li><b>不采纳旧系统</b>「押金按月自动返还 + 到期硬编码奖励 200 元」——返还由退租结算动作驱动
 *       （友宝也是期满返还），奖励属营销层不入账务。</li>
 * </ol>
 */
@Service
public class DeviceRentalService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRentalService.class);

    /** 欠租锁机原因前缀（解锁判定依据，改前先看 runDailyCheck 的解锁分支）。 */
    public static final String RENTAL_LOCK_PREFIX = "欠租锁柜";

    static final String CFG_OVERDUE_LOCK_DAYS = "device.rental.overdue_lock_days";

    private final DeviceRentalDepositMapper depositMapper;
    private final DeviceRentalDepositEventMapper depositEventMapper;
    private final DeviceRentalBillMapper billMapper;
    private final DeviceInfoMapper deviceMapper;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final SystemConfigService systemConfigService;
    private final DeviceSalesLockService salesLockService;
    private final FeeBillMonthResolver monthResolver;
    private final DistributedLockService distributedLockService;
    private final Clock clock;

    public DeviceRentalService(DeviceRentalDepositMapper depositMapper,
                               DeviceRentalDepositEventMapper depositEventMapper,
                               DeviceRentalBillMapper billMapper,
                               DeviceInfoMapper deviceMapper,
                               PermissionService permissionService,
                               AdminAuditService auditService,
                               SystemConfigService systemConfigService,
                               DeviceSalesLockService salesLockService,
                               FeeBillMonthResolver monthResolver,
                               DistributedLockService distributedLockService) {
        this(depositMapper, depositEventMapper, billMapper, deviceMapper, permissionService,
                auditService, systemConfigService, salesLockService, monthResolver, distributedLockService,
                Clock.systemDefaultZone());
    }

    @Autowired // 双构造器（10 参便捷版供测试）必须显式指定 Spring 注入点，否则启动报 Clock bean 缺失
    public DeviceRentalService(DeviceRentalDepositMapper depositMapper,
                               DeviceRentalDepositEventMapper depositEventMapper,
                               DeviceRentalBillMapper billMapper,
                               DeviceInfoMapper deviceMapper,
                               PermissionService permissionService,
                               AdminAuditService auditService,
                               SystemConfigService systemConfigService,
                               DeviceSalesLockService salesLockService,
                               FeeBillMonthResolver monthResolver,
                               DistributedLockService distributedLockService,
                               Clock clock) {
        this.depositMapper = depositMapper;
        this.depositEventMapper = depositEventMapper;
        this.billMapper = billMapper;
        this.deviceMapper = deviceMapper;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.systemConfigService = systemConfigService;
        this.salesLockService = salesLockService;
        this.monthResolver = monthResolver;
        this.distributedLockService = distributedLockService;
        this.clock = clock;
    }

    // ── 押金台账 ──────────────────────────────────────────────────────────

    /** 押金登记（线下收款后收据化；每台柜至多一条未结算押金）。 */
    @Transactional
    public DeviceRentalDeposit registerDeposit(Long operatorId, String deviceId, long amountCents, String remark) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        DeviceInfo device = requireRentalDevice(deviceId);
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "押金金额必须大于 0");
        }
        if (depositMapper.findByDevice(deviceId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该柜机已登记押金，请先用扣款/结算处置原押金");
        }
        DeviceRentalDeposit deposit = new DeviceRentalDeposit();
        deposit.setDeviceId(deviceId);
        deposit.setMerchantId(device.getMerchantId());
        deposit.setAmountCents(amountCents);
        deposit.setBalanceCents(amountCents);
        deposit.setRemark(trim(remark));
        depositMapper.insert(deposit);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_DEPOSIT_REGISTER", "DEVICE", deviceId,
                "amount=" + amountCents);
        return deposit;
    }

    /** 押金扣款抵扣（欠租/验机损坏等，必须填依据；Σ扣款 ≤ 本金由余额非负约束保证）。 */
    @Transactional
    public DeviceRentalDeposit deduct(Long operatorId, Long depositId, long amountCents, String reason) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        DeviceRentalDeposit deposit = requireDeposit(depositId);
        if (!DeviceRentalDeposit.STATUS_HELD.equals(deposit.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "押金已结算，不能再扣款");
        }
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "扣款金额必须大于 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "扣款必须填写依据（欠租账单/验机损坏等）");
        }
        if (depositMapper.adjustBalance(depositId, -amountCents) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "扣款超过剩余可退押金（余额 " + deposit.getBalanceCents() + " 分）");
        }
        insertEvent(depositId, DeviceRentalDeposit.EVENT_DEDUCT, -amountCents, reason, operatorId);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_DEPOSIT_DEDUCT", "DEVICE", deposit.getDeviceId(),
                "amount=" + amountCents + " reason=" + reason.trim());
        return depositMapper.selectById(depositId);
    }

    /** 结算返还（线下把剩余押金退给承租方后登记收据；余额即返还净额）。 */
    @Transactional
    public DeviceRentalDeposit settle(Long operatorId, Long depositId, String remark) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        DeviceRentalDeposit deposit = requireDeposit(depositId);
        if (DeviceRentalDeposit.STATUS_SETTLED.equals(deposit.getStatus())) {
            return deposit;
        }
        long refund = deposit.getBalanceCents();
        if (refund > 0) {
            if (depositMapper.adjustBalance(depositId, -refund) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "押金余额推进失败，请重试");
            }
            insertEvent(depositId, DeviceRentalDeposit.EVENT_RETURN, -refund,
                    remark == null || remark.isBlank() ? "退租结算返还" : remark.trim(), operatorId);
        }
        deposit = depositMapper.selectById(depositId);
        deposit.setStatus(DeviceRentalDeposit.STATUS_SETTLED);
        deposit.setSettledAt(Instant.now(clock));
        deposit.setUpdatedAt(Instant.now(clock));
        depositMapper.updateById(deposit);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_DEPOSIT_SETTLE", "DEVICE", deposit.getDeviceId(),
                "refund=" + refund);
        return deposit;
    }

    public List<DeviceRentalDepositEvent> listDepositEvents(Long depositId) {
        return depositEventMapper.findByDepositOrderByCreatedDesc(depositId);
    }

    public List<DeviceRentalDeposit> listDeposits() {
        return depositMapper.selectList(null);
    }

    // ── 月租账单 ──────────────────────────────────────────────────────────

    public PageResult<DeviceRentalBill> listBills(Long operatorId, String billMonth, String status,
                                                  String deviceId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:fund:list");
        int p = monthResolver.clampPage(page);
        int s = monthResolver.clampPageSize(size);
        var result = billMapper.searchPage(blankToNull(billMonth), blankToNull(status), blankToNull(deviceId), p, s);
        return new PageResult<>(result.getRecords(), p, s, result.getTotal());
    }

    /** 单柜出账（仅 RENTAL 且 rentCents>0；账期 ≥ 起租账期）。 */
    @Transactional
    public DeviceRentalBill generateBill(Long operatorId, String deviceId, String billMonth) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        String month = monthResolver.resolve(billMonth);
        return runWithLock(deviceId, month, () -> doGenerateOne(operatorId, deviceId, month));
    }

    /** 全量出账（手动）。 */
    @Transactional
    public List<DeviceRentalBill> generateAll(Long operatorId, String billMonth) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        return generateAllInternal(operatorId, monthResolver.resolve(billMonth));
    }

    @Transactional
    public DeviceRentalBill markPaid(Long operatorId, Long billId) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        DeviceRentalBill bill = requireBill(billId);
        if (CabinetConstants.FEE_BILL_STATUS_VOID.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已作废账单不可标记已付");
        }
        if (CabinetConstants.FEE_BILL_STATUS_PAID.equals(bill.getStatus())) {
            return bill;
        }
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_PAID);
        bill.setPaidAt(Instant.now(clock));
        bill.setUpdatedAt(Instant.now(clock));
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_BILL_PAID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth() + " amount=" + bill.getAmountCents());
        return bill;
    }

    @Transactional
    public DeviceRentalBill voidBill(Long operatorId, Long billId) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        DeviceRentalBill bill = requireBill(billId);
        if (CabinetConstants.FEE_BILL_STATUS_PAID.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已付账单不可作废");
        }
        if (CabinetConstants.FEE_BILL_STATUS_VOID.equals(bill.getStatus())) {
            return bill;
        }
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_VOID);
        bill.setUpdatedAt(Instant.now(clock));
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_BILL_VOID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth());
        return bill;
    }

    private DeviceRentalBill doGenerateOne(Long operatorId, String deviceId, String month) {
        if (operatorId != null) {
            permissionService.requirePermission(operatorId, "ops:rental:manage");
        }
        DeviceInfo device = requireRentalDevice(deviceId);
        long rent = device.getRentCents() == null ? 0L : device.getRentCents();
        if (rent <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该柜机未配置租赁月租（rentCents）或为 0，无法出账");
        }
        if (device.getRentStartYm() != null && month.compareTo(device.getRentStartYm()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "账期 " + month + " 早于起租账期 " + device.getRentStartYm());
        }
        if (billMapper.countNonVoidByDeviceAndMonth(deviceId, month) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该柜机 " + month + " 租金已出账");
        }
        Instant now = Instant.now(clock);
        DeviceRentalBill bill = new DeviceRentalBill();
        bill.setDeviceId(deviceId);
        bill.setDeviceName(device.getDeviceName());
        bill.setMerchantId(device.getMerchantId());
        bill.setBillMonth(month);
        bill.setAmountCents(rent);
        bill.setRemark("由设备租赁月租（分/月）快照生成；线下收款人工标记，不自动扣款");
        bill.setCreatedAt(now);
        bill.setUpdatedAt(now);
        billMapper.insert(bill);
        if (operatorId != null) {
            auditService.appendLog(operatorId, "DEVICE_RENTAL_BILL_GEN", "DEVICE", deviceId,
                    "month=" + month + " amount=" + rent);
        }
        return bill;
    }

    private List<DeviceRentalBill> generateAllInternal(Long operatorId, String month) {
        List<DeviceRentalBill> created = new ArrayList<>();
        for (DeviceInfo device : deviceMapper.selectList(null)) {
            long rent = device.getRentCents() == null ? 0L : device.getRentCents();
            if (!"RENTAL".equals(device.getCoopMode()) || rent <= 0) {
                continue;
            }
            if (device.getRentStartYm() != null && month.compareTo(device.getRentStartYm()) < 0) {
                continue;
            }
            if (billMapper.countNonVoidByDeviceAndMonth(device.getDeviceId(), month) > 0) {
                continue;
            }
            created.add(runWithLock(device.getDeviceId(), month,
                    () -> doGenerateOne(operatorId, device.getDeviceId(), month)));
        }
        log.info("rental bills generated month={} count={} operatorId={}", month, created.size(), operatorId);
        return created;
    }

    // ── 每日任务：出账 + 欠租锁柜核对 ─────────────────────────────────────

    /**
     * 租赁日检（device-rental-daily，每日 08:20）：
     * ① 当前账期租金幂等出账；② 逾期 UNPAID 账单锁柜；③ 已结清且因欠租被锁的柜机解锁。
     *
     * @return 人读摘要（运营台任务历史可见）
     */
    @Transactional
    public String runDailyCheck() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        String currentMonth = YearMonth.from(today).toString();
        int graceDays = Math.max(0, systemConfigService.getInt(CFG_OVERDUE_LOCK_DAYS, 15));

        StringBuilder summary = new StringBuilder();

        // ① 出账（幂等；生成失败不阻断锁柜核对）
        // 2026-10-14：费用改日扣模式（DAILY_FEE 每日摊销扣余额）后，月度应收出账默认停用，
        // 否则同一租金既日扣又月出账会重复计费。开关 device.rental.monthly_bill.enabled 可回退。
        boolean monthlyBillEnabled = systemConfigService
                .getValue("device.rental.monthly_bill.enabled", "false")
                .equalsIgnoreCase("true");
        int generated = 0;
        if (monthlyBillEnabled) {
            try {
                generated = generateAllInternal(null, currentMonth).size();
            } catch (Exception e) {
                log.error("rental bill generation failed on daily check", e);
            }
            summary.append("出账 ").append(generated).append(" 笔；");
        } else {
            summary.append("月度出账已停用（日扣模式）；");
        }

        // ②+③ 欠租锁柜 / 结清解锁
        String overdueBeforeMonth = YearMonth.from(today.minusDays(graceDays + 1L)).toString();
        List<DeviceRentalBill> unpaid = billMapper.findUnpaidUpTo(overdueBeforeMonth);
        List<String> overdueDevices = unpaid.stream().map(DeviceRentalBill::getDeviceId).distinct().toList();

        int locked = 0;
        for (String deviceId : overdueDevices) {
            DeviceRentalBill oldest = unpaid.stream()
                    .filter(b -> b.getDeviceId().equals(deviceId))
                    .findFirst().orElse(null);
            if (oldest == null) {
                continue;
            }
            DeviceInfo device = deviceMapper.findById(deviceId).orElse(null);
            if (device == null || Boolean.TRUE.equals(device.getSalesLocked())) {
                // 已锁（不论原因）不再重复下发
                if (device != null) {
                    markBillLockAt(oldest);
                }
                continue;
            }
            try {
                salesLockService.applySalesLock(null, device, true,
                        RENTAL_LOCK_PREFIX + "：" + oldest.getBillMonth() + " 起租金未结清", true);
                markBillLockAt(oldest);
                auditService.appendLog(null, "DEVICE_RENTAL_OVERDUE_LOCK", "DEVICE", deviceId,
                        "oldestMonth=" + oldest.getBillMonth());
                locked++;
            } catch (Exception e) {
                log.error("rental overdue lock failed deviceId={}", deviceId, e);
            }
        }
        summary.append("欠租锁柜 ").append(locked).append(" 台；");

        int unlocked = 0;
        for (DeviceInfo device : deviceMapper.selectList(null)) {
            if (!Boolean.TRUE.equals(device.getSalesLocked())
                    || device.getSalesLockReason() == null
                    || !device.getSalesLockReason().startsWith(RENTAL_LOCK_PREFIX)) {
                continue;
            }
            if (overdueDevices.contains(device.getDeviceId())) {
                continue;
            }
            try {
                salesLockService.applySalesLock(null, device, false, "租金已结清，解除欠租锁柜", true);
                auditService.appendLog(null, "DEVICE_RENTAL_OVERDUE_UNLOCK", "DEVICE", device.getDeviceId(), null);
                unlocked++;
            } catch (Exception e) {
                log.error("rental overdue unlock failed deviceId={}", device.getDeviceId(), e);
            }
        }
        summary.append("结清解锁 ").append(unlocked).append(" 台");
        return summary.toString();
    }

    private void markBillLockAt(DeviceRentalBill bill) {
        if (bill.getLockAt() != null) {
            return;
        }
        bill.setLockAt(Instant.now(clock));
        bill.setUpdatedAt(Instant.now(clock));
        billMapper.updateById(bill);
    }

    // ── 公共 ──────────────────────────────────────────────────────────────

    private DeviceInfo requireRentalDevice(String deviceId) {
        DeviceInfo device = deviceMapper.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        if (!"RENTAL".equals(device.getCoopMode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该柜机合作模式非 RENTAL，不能登记租赁押金/出租赁账单");
        }
        return device;
    }

    private DeviceRentalDeposit requireDeposit(Long depositId) {
        DeviceRentalDeposit deposit = depositMapper.selectById(depositId);
        if (deposit == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "押金台账不存在");
        }
        return deposit;
    }

    private DeviceRentalBill requireBill(Long billId) {
        DeviceRentalBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租赁账单不存在");
        }
        return bill;
    }

    private void insertEvent(Long depositId, String type, long amountCents, String reason, Long operatorId) {
        DeviceRentalDepositEvent event = new DeviceRentalDepositEvent();
        event.setDepositId(depositId);
        event.setEventType(type);
        event.setAmountCents(amountCents);
        event.setReason(reason.trim());
        event.setOperatorId(operatorId);
        event.setCreatedAt(Instant.now(clock));
        depositEventMapper.insert(event);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    static String billLockKey(String deviceId, String billMonth) {
        return "device-rental-bill:" + deviceId + ":" + billMonth;
    }

    private <T> T runWithLock(String deviceId, String billMonth, java.util.function.Supplier<T> action) {
        String key = billLockKey(deviceId, billMonth);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租赁账单生成中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }
}
