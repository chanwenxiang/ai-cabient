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
 *   <li><b>押金是负债不是收入</b>：不走平台资金池台账。收取方式按 {@code charge_mode} 分两条：
 *       <b>ONLINE</b>=登记即从商户余额真扣、结算时净额回入商户余额（走钱包幂等，可负=欠款，见
 *       {@code registerDeposit} 的 DEPOSIT_CHARGE 与 {@code settle} 的 DEPOSIT_RETURN）；
 *       <b>OFFLINE</b>=线下收缴只做收据化登记、系统不动钱（存量行为主）。
 *       两种模式都：登记→扣款抵扣→结算返还，每笔扣款必须填依据并留审计。</li>
 *   <li><b>租金是应收不是应付</b>：与 DeviceDataFeeBill 同为<b>商户应付</b>（柜机流量费由商户承担，
 *       快照口径分/天，2026-10-16 F-3 拍板；日扣模式下常规走钱包自动扣，账单页仅人工留痕）。
 *       本服务的租金收款仍由人工标记，不自动扣款（不碰资金动作）。</li>
 *   <li><b>欠租锁柜复用 DeviceSalesLockService</b>：逾期 UNPAID 账单 → 锁机；结清 → 解锁。
 *       解锁只针对「因欠租而锁」的柜机（salesLockReason 以 RENTAL_LOCK_PREFIX 开头），
 *       不碰其它原因的锁（维修/风控等各有自己的解锁路径）。</li>
 *   <li><b>押金返还是负债清偿不是收入</b>：主路径由退租结算动作驱动（友宝也是期满返还）。
 *       「按月自动返还 + 期满奖励」在 V360 采纳为<b>登记可配</b>参数（CB-034 口径往返见该条 ⚠️ 补记），
 *       但 V376（2026-10-16 DC-29）把 {@code deposit-monthly-refund} 种子行默认改 FALSE
 *       ⇒ <b>现行默认口径仍是人工退押</b>，自动返还需运营台显式打开该任务；奖励额不入账务、登记可填。</li>
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
    private final MerchantWalletService merchantWalletService;
    private final ScheduledTaskService taskService;
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
                               DistributedLockService distributedLockService,
                               ScheduledTaskService taskService) {
        this(depositMapper, depositEventMapper, billMapper, deviceMapper, permissionService,
                auditService, systemConfigService, salesLockService, monthResolver, distributedLockService,
                null, taskService, Clock.systemDefaultZone());
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
                               MerchantWalletService merchantWalletService,
                               ScheduledTaskService taskService,
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
        this.merchantWalletService = merchantWalletService;
        this.taskService = taskService;
        this.clock = clock;
    }

    // ── 押金台账 ──────────────────────────────────────────────────────────

    /**
     * 押金登记（2026-10-14 对齐老系统）：charge_mode=ONLINE 时**从商户余额真扣押金**
     * （老系统 OptUserAccount.amountCashAvailable 直减可负——押金是负债，余额不足也收取成功、
     * 欠款挂余额）；金额/月退额/起始月/期满奖励全部登记时按柜填写（不写死）。
     * 存量 OFFLINE 行（线下收缴收据化）不参与线上自动退还，避免双退。
     */
    @Transactional
    public DeviceRentalDeposit registerDeposit(Long operatorId, String deviceId, long amountCents, String remark) {
        return registerDeposit(operatorId, deviceId, amountCents, remark,
                null, null, null);
    }

    @Transactional
    public DeviceRentalDeposit registerDeposit(Long operatorId, String deviceId, long amountCents, String remark,
                                               Long monthlyRefundCents, Integer refundStartMonth,
                                               Long expireRewardCents) {
        return registerDeposit(operatorId, deviceId, amountCents, remark,
                monthlyRefundCents, refundStartMonth, expireRewardCents, null, null, null);
    }

    /**
     * 撤机清算三件套默认值。安装费默认 <b>0=不收</b>（用户 2026-10-17 拍板「这个费用默认为 0，
     * 做成按柜子配置」；V383 同步列默认）——老系统是全局硬编码 100 元、不分柜不分商户，
     * 行业撤机用「违约金/损失抵偿」名目（CB-044），故开箱不扣、要收就登记时逐柜填。
     */
    static final long DEFAULT_INSTALL_FEE_DEDUCT_CENTS = 0L;
    static final int DEFAULT_CONTRACT_YEARS = 5;

    @Transactional
    public DeviceRentalDeposit registerDeposit(Long operatorId, String deviceId, long amountCents, String remark,
                                               Long monthlyRefundCents, Integer refundStartMonth,
                                               Long expireRewardCents,
                                               Long installFeeDeductCents, Boolean installFeeClawbackEnabled,
                                               Integer contractYears) {
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
        deposit.setChargeMode("ONLINE");
        if (monthlyRefundCents != null && monthlyRefundCents > 0) {
            deposit.setMonthlyRefundCents(monthlyRefundCents);
            deposit.setRefundStartMonth(refundStartMonth == null || refundStartMonth < 1
                    ? 3 : refundStartMonth);
        }
        deposit.setExpireRewardCents(expireRewardCents == null ? 0L : Math.max(0L, expireRewardCents));
        // CB-044 拍板：撤机清算三件套登记可配（空值=老系统口径默认）
        deposit.setInstallFeeDeductCents(installFeeDeductCents == null
                ? DEFAULT_INSTALL_FEE_DEDUCT_CENTS : Math.max(0L, installFeeDeductCents));
        // DC-29（2026-10-16 拍板「靠运营台手动开」）：倒扣商户余额属资损面 ⇒ 空值不再等于老系统的「开」，
        // 必须显式传 true 才启用（与 V376 的列默认 FALSE 同向；运营台登记表单暂未暴露该参数）。
        deposit.setInstallFeeClawbackEnabled(Boolean.TRUE.equals(installFeeClawbackEnabled));
        if (contractYears != null && contractYears < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同年数必须 ≥ 1");
        }
        deposit.setContractYears(contractYears == null ? DEFAULT_CONTRACT_YEARS : contractYears);
        deposit.setRemark(trim(remark));
        depositMapper.insert(deposit);
        // 线上收取：从商户余额真扣（幂等 refType+refId；可负=欠款挂余额，老系统同款）
        merchantWalletService.debitIfAbsentAllowNegative(deposit.getMerchantId(), amountCents,
                "DEPOSIT_CHARGE", "RENTAL_DEPOSIT", String.valueOf(deposit.getDepositId()),
                "押金收取（柜机 " + deviceId + "）");
        insertEvent(deposit.getDepositId(), DeviceRentalDeposit.EVENT_CHARGE, amountCents,
                "押金收取（从商户余额扣，线上）", operatorId);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_DEPOSIT_REGISTER", "DEVICE", deviceId,
                "amount=" + amountCents + " monthlyRefund=" + deposit.getMonthlyRefundCents()
                        + " startMonth=" + deposit.getRefundStartMonth()
                        + " reward=" + deposit.getExpireRewardCents());
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

    /**
     * 结算返还（撤机清算，CB-044 拍板 2026-10-15 选项 1：复刻老三件套，费用参数登记可配不写死）：
     * <ul>
     *   <li><b>未满合同年数撤机</b>：退剩余押金 − 撤机安装费（installFeeDeductCents，默认 100 元，
     *       从押金内扣，最多扣到 0）；押金已按月退完且 installFeeClawbackEnabled（<b>默认关</b>，
     *       DC-29 拍板；登记时显式传 true 才启用）⇒
     *       向商户余额<b>倒扣</b>安装费（AllowNegative，可挂负，对齐老系统追回）。</li>
     *   <li><b>满 contractYears（默认 5）年撤机</b>：免扣安装费，发期满奖励 expireRewardCents
     *       （refId 与月度退还链同款 {@code depositId|REWARD}——幂等，月度链已发过则不双发）。</li>
     * </ul>
     * OFFLINE（线下收缴存量行）不向商户余额进钱/倒扣，只做台账事件留痕（同 V360 防双退纪律）。
     */
    @Transactional
    public DeviceRentalDeposit settle(Long operatorId, Long depositId, String remark) {
        permissionService.requirePermission(operatorId, "ops:rental:manage");
        DeviceRentalDeposit deposit = requireDeposit(depositId);
        if (DeviceRentalDeposit.STATUS_SETTLED.equals(deposit.getStatus())) {
            return deposit;
        }
        long installFee = deposit.getInstallFeeDeductCents() == null
                ? DEFAULT_INSTALL_FEE_DEDUCT_CENTS : deposit.getInstallFeeDeductCents();
        boolean clawback = Boolean.TRUE.equals(deposit.getInstallFeeClawbackEnabled());
        int contractYears = deposit.getContractYears() == null ? 5 : deposit.getContractYears();
        boolean contractExpired = contractExpired(deposit, contractYears);

        long refund = deposit.getBalanceCents();
        if (refund > 0) {
            // 老口径：押金未退完撤机 → 一律退剩余 − 安装费（满期豁免只作用于「已退完」分支）
            long fee = Math.min(Math.max(0L, installFee), refund);
            long net = refund - fee;
            if (depositMapper.adjustBalance(depositId, -refund) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "押金余额推进失败，请重试");
            }
            if ("ONLINE".equals(deposit.getChargeMode())) {
                if (net > 0) {
                    // 线上押金：扣完安装费的剩余额退入商户余额（幂等），对齐老系统一次性结清分支
                    merchantWalletService.creditIfAbsent(deposit.getMerchantId(), net,
                            "DEPOSIT_RETURN", "RENTAL_DEPOSIT_SETTLE", String.valueOf(depositId),
                            fee > 0 ? "退租结算返还（已扣撤机安装费 " + fee + " 分，柜机 "
                                    + deposit.getDeviceId() + "）"
                                    : "退租结算返还（柜机 " + deposit.getDeviceId() + "）");
                    insertEvent(depositId, DeviceRentalDeposit.EVENT_RETURN, -net,
                            remark == null || remark.isBlank() ? "退租结算返还" : remark.trim(), operatorId);
                }
                if (fee > 0) {
                    // 安装费=平台收入：从押金扣下不入商户余额（对齐老系统 INSTALL_FEE_DEDUCT）
                    insertEvent(depositId, DeviceRentalDeposit.EVENT_DEDUCT, -fee,
                            "撤机安装费扣减（未满 " + contractYears + " 年）", operatorId);
                }
            } else {
                insertEvent(depositId, DeviceRentalDeposit.EVENT_RETURN, -refund,
                        remark == null || remark.isBlank() ? "退租结算返还（线下）" : remark.trim(),
                        operatorId);
            }
        } else if (contractExpired) {
            // 押金已按月退完 + 满 contractYears：发期满奖励（refId 与月度退还链一致 ⇒ 幂等不双发）
            long reward = deposit.getExpireRewardCents() == null ? 0L : deposit.getExpireRewardCents();
            if (reward > 0 && "ONLINE".equals(deposit.getChargeMode())) {
                boolean credited = merchantWalletService.creditIfAbsent(deposit.getMerchantId(), reward,
                        "DEPOSIT_EXPIRE_REWARD", "RENTAL_DEPOSIT_REFUND",
                        depositId + "|REWARD",
                        "押金撤机期满奖励（柜机 " + deposit.getDeviceId() + "）");
                if (credited) {
                    insertEvent(depositId, DeviceRentalDeposit.EVENT_RETURN, reward,
                            "撤机期满奖励（满 " + contractYears + " 年）", operatorId);
                }
            }
        } else if (clawback && installFee > 0 && "ONLINE".equals(deposit.getChargeMode())) {
            // 押金已退完但未满年数：向商户余额倒扣安装费（可挂负，对齐老系统追回；refId 幂等）
            boolean deducted = merchantWalletService.debitIfAbsentAllowNegative(deposit.getMerchantId(),
                    installFee, "DEPOSIT_INSTALL_FEE_CLAWBACK", "RENTAL_DEPOSIT_SETTLE",
                    depositId + "|CLAWBACK",
                    "撤机安装费追回（未满 " + contractYears + " 年，柜机 " + deposit.getDeviceId() + "）");
            if (deducted) {
                insertEvent(depositId, DeviceRentalDeposit.EVENT_DEDUCT, -installFee,
                        "撤机安装费追回（已按月退完，未满 " + contractYears + " 年）", operatorId);
            }
        }
        deposit = depositMapper.selectById(depositId);
        deposit.setStatus(DeviceRentalDeposit.STATUS_SETTLED);
        deposit.setSettledAt(Instant.now(clock));
        deposit.setUpdatedAt(Instant.now(clock));
        depositMapper.updateById(deposit);
        auditService.appendLog(operatorId, "DEVICE_RENTAL_DEPOSIT_SETTLE", "DEVICE", deposit.getDeviceId(),
                "refund=" + refund + " installFee=" + installFee + " expired=" + contractExpired);
        return deposit;
    }

    /** 登记是否满 contractYears（老系统 gmtCreated+5 年口径）；createdAt 缺失按未满期保守处理。 */
    private boolean contractExpired(DeviceRentalDeposit deposit, int contractYears) {
        if (deposit.getCreatedAt() == null || contractYears <= 0) {
            return false;
        }
        java.time.LocalDate created =
                java.time.LocalDate.ofInstant(deposit.getCreatedAt(), java.time.ZoneId.systemDefault());
        return !created.plusYears(contractYears).isAfter(java.time.LocalDate.now(clock));
    }

    // ── 押金按月退还（对齐老系统 MachineGuaranteeCashTask，2026-10-14 拍板） ──

    /** 事件类型：线上收取（对齐老系统 GUARANTEE_CASH_SAVE）。 */
    public static final String DEPOSIT_REFUND_TASK_KEY = "deposit-monthly-refund";

    /**
     * 每月退还（由 DepositMonthlyRefundJob 每月 1 日驱动）：遍历未结清押金
     * （charge_mode=ONLINE 且月退额>0），自登记月起第 refundStartMonth 个月开始每月退
     * monthlyRefundCents 入商户余额（幂等 refId=押金+月序）；第 threshold 月（=总额/月退额
     * +起始月-1，老系统同款公式）退完置结清，同 run 按 expireRewardCents 发期满奖励。
     * @return 已处理退还笔数
     */
    @Transactional
    public int runMonthlyRefunds() {
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        int monthIndex = today.getYear() * 12 + today.getMonthValue();
        List<DeviceRentalDeposit> held = depositMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<DeviceRentalDeposit>lambdaQuery()
                        .eq(DeviceRentalDeposit::getStatus, DeviceRentalDeposit.STATUS_HELD)
                        .eq(DeviceRentalDeposit::getChargeMode, "ONLINE")
                        .isNotNull(DeviceRentalDeposit::getMonthlyRefundCents)
                        .gt(DeviceRentalDeposit::getMonthlyRefundCents, 0L));
        int processed = 0;
        for (DeviceRentalDeposit deposit : held) {
            java.time.LocalDate start = deposit.getCreatedAt() == null
                    ? null : java.time.LocalDate.ofInstant(deposit.getCreatedAt(), java.time.ZoneId.systemDefault())
                    .withDayOfMonth(1);
            if (start == null) {
                continue;
            }
            int monthFromStart = (today.getYear() * 12 + today.getMonthValue())
                    - (start.getYear() * 12 + start.getMonthValue()) + 1;
            int startMonth = deposit.getRefundStartMonth() == null ? 3 : deposit.getRefundStartMonth();
            long unit = deposit.getMonthlyRefundCents();
            long threshold = (deposit.getAmountCents() + unit - 1) / unit + startMonth - 1;
            if (monthFromStart < startMonth || monthFromStart > threshold) {
                continue;
            }
            // 🔴 末月退余额（amount − 已退累计）而非固定 unit，防 ceil 超退（审计 🔴3）：
            // 月退额不能整除总额时，最后一个月退剩余部分，正好归零 → 结清+期满奖励可达。
            long unitThisMonth = monthFromStart == threshold
                    ? deposit.getBalanceCents() : Math.min(unit, deposit.getBalanceCents());
            if (unitThisMonth <= 0) {
                continue;
            }
            // 🔴 顺序修正（审计 🔴3）：先扣押金台账余额（守卫失败=本事务回滚，不会出现
            // 「钱包已入账、押金未减」的超额退还），再入商户钱包（幂等 refId=押金+月序）。
            if (depositMapper.adjustBalance(deposit.getDepositId(), -unitThisMonth) == 0) {
                continue;
            }
            String refId = deposit.getDepositId() + "|" + monthFromStart;
            boolean charged = merchantWalletService.creditIfAbsent(deposit.getMerchantId(), unitThisMonth,
                    "DEPOSIT_RETURN", "RENTAL_DEPOSIT_REFUND", refId,
                    "押金按月退还（第 " + monthFromStart + " 个月，柜机 " + deposit.getDeviceId() + "）");
            if (!charged) {
                // 钱包流水已存在（重跑）：台账余额已扣 → 回补台账余额，保持两账一致
                depositMapper.adjustBalance(deposit.getDepositId(), unitThisMonth);
                continue;
            }
            insertEvent(deposit.getDepositId(), DeviceRentalDeposit.EVENT_RETURN, -unitThisMonth,
                    "按月退还（第 " + monthFromStart + "/" + threshold + " 个月）", null);
            processed++;
            boolean last = monthFromStart == threshold;
            if (last) {
                DeviceRentalDeposit fresh = depositMapper.selectById(deposit.getDepositId());
                fresh.setStatus(DeviceRentalDeposit.STATUS_SETTLED);
                fresh.setSettledAt(Instant.now(clock));
                fresh.setUpdatedAt(Instant.now(clock));
                depositMapper.updateById(fresh);
                long reward = deposit.getExpireRewardCents() == null ? 0L : deposit.getExpireRewardCents();
                if (reward > 0) {
                    merchantWalletService.creditIfAbsent(deposit.getMerchantId(), reward,
                            "DEPOSIT_EXPIRE_REWARD", "RENTAL_DEPOSIT_REFUND",
                            deposit.getDepositId() + "|REWARD",
                            "押金期满奖励（柜机 " + deposit.getDeviceId() + "）");
                    insertEvent(deposit.getDepositId(), DeviceRentalDeposit.EVENT_RETURN, reward,
                            "期满奖励", null);
                }
            }
        }
        return processed;
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
        // AU-173（2026-10-18 批次 1）：39 任务唯二漏配执行守卫 ⇒ 无 job:* 分布式锁（多副本双跑）
        // 且 scheduled_task.enabled 停用开关被旁路（上线手册「资金任务保持关」关不住）。
        // 锁窗口=本事务期（unlock 由 finish 在提交前触发）：写侧出账/锁柜均幂等 + XXL cron 不重叠，
        // 残余窗口可接受；若后续拆事务须把守卫移到事务外。
        if (!taskService.tryBegin("device-rental-daily", 1800)) {
            return "skipped";
        }
        long start = System.nanoTime();
        try {
            String summary = doDailyCheck();
            taskService.finish("device-rental-daily", "SUCCESS", summary, start);
            return summary;
        } catch (Exception e) {
            taskService.finish("device-rental-daily", "FAILED", e.getMessage(), start);
            throw e;
        }
    }

    private String doDailyCheck() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        String currentMonth = YearMonth.from(today).toString();
        int graceDays = Math.max(0, systemConfigService.getInt(CFG_OVERDUE_LOCK_DAYS, 15));

        StringBuilder summary = new StringBuilder();

        // ① 出账（幂等；生成失败不阻断锁柜核对）
        // 2026-10-14：费用改日扣模式（DAILY_FEE 每日摊销扣余额）后，月度应收出账默认停用，
        // 否则同一租金既日扣又月出账会重复计费。开关 device.rental.monthly_bill.enabled 可回退。
        // getValue 桩/缺配置可能返回 null——常量在前防 NPE
        boolean monthlyBillEnabled = "true".equalsIgnoreCase(systemConfigService
                .getValue("device.rental.monthly_bill.enabled", "false"));
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
