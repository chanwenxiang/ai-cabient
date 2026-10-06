package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.GenerateMonthlyFeeBillsRequest;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.SiteRentBillDto;
import com.aicabinet.trade.domain.SiteContract;
import com.aicabinet.trade.domain.SiteRentBill;
import com.aicabinet.trade.domain.SiteRentSplitRule;
import com.aicabinet.trade.mapper.SiteContractMapper;
import com.aicabinet.trade.mapper.SiteRentBillMapper;
import com.aicabinet.trade.mapper.SiteRentSplitRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 场地租金应付账单：按合同月费与分账规则出账；人工标记已付，不触发自动打款。
 */
@Service
public class SiteRentBillService {

    private static final Logger log = LoggerFactory.getLogger(SiteRentBillService.class);

    private final SiteRentBillMapper billMapper;
    private final SiteContractMapper contractMapper;
    private final SiteRentSplitRuleMapper ruleMapper;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final FeeBillMonthResolver monthResolver;

    public SiteRentBillService(SiteRentBillMapper billMapper,
                               SiteContractMapper contractMapper,
                               SiteRentSplitRuleMapper ruleMapper,
                               PermissionService permissionService,
                               AdminAuditService auditService,
                               DistributedLockService distributedLockService,
                               FeeBillMonthResolver monthResolver) {
        this.billMapper = billMapper;
        this.contractMapper = contractMapper;
        this.ruleMapper = ruleMapper;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.monthResolver = monthResolver;
    }

    @Transactional(readOnly = true)
    public PageResult<SiteRentBillDto> list(Long operatorId, String billMonth, String status,
                                            Long contractId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:org:list");
        int p = monthResolver.clampPage(page);
        int s = monthResolver.clampPageSize(size);
        var result = billMapper.searchPage(blankToNull(billMonth), blankToNull(status), contractId, p, s);
        return new PageResult<>(result.getRecords().stream().map(this::toDto).toList(), p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public List<SiteRentBillDto> listByContract(Long operatorId, Long contractId, String billMonth) {
        permissionService.requirePermission(operatorId, "ops:org:list");
        requireContract(contractId);
        if (billMonth == null || billMonth.isBlank()) {
            return billMapper.searchPage(null, null, contractId, 1, monthResolver.clampPageSize(null))
                    .getRecords().stream().map(this::toDto).toList();
        }
        String month = monthResolver.requireValid(billMonth);
        return billMapper.findByContractAndMonth(contractId, month).stream().map(this::toDto).toList();
    }

    @Transactional
    public List<SiteRentBillDto> generateForContract(Long operatorId, Long contractId,
                                                     GenerateMonthlyFeeBillsRequest request) {
        String month = monthResolver.resolve(request == null ? null : request.billMonth());
        return runWithBillLock(contractId, month, () -> doGenerateForContract(operatorId, contractId, month));
    }

    @Transactional
    public List<SiteRentBillDto> generateForAllActive(Long operatorId, GenerateMonthlyFeeBillsRequest request) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        return generateAllInternal(operatorId, monthResolver.resolve(request == null ? null : request.billMonth()));
    }

    /** 定时任务入口：无操作员鉴权。 */
    @Transactional
    public List<SiteRentBillDto> autoGenerate(String billMonthOrBlank) {
        return generateAllInternal(null, monthResolver.resolve(billMonthOrBlank));
    }

    private List<SiteRentBillDto> generateAllInternal(Long operatorId, String month) {
        List<SiteRentBillDto> created = new ArrayList<>();
        for (SiteContract c : contractMapper.findAllOrderByUpdatedDesc()) {
            if (!CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(c.getStatus())
                    && !"EXPIRING".equalsIgnoreCase(c.getStatus())) {
                continue;
            }
            if (billMapper.countNonVoidByContractAndMonth(c.getContractId(), month) > 0) {
                continue;
            }
            created.addAll(runWithBillLock(c.getContractId(), month,
                    () -> doGenerateForContract(operatorId, c.getContractId(), month)));
        }
        log.info("site rent bills generated for month={} count={} operatorId={}", month, created.size(), operatorId);
        return created;
    }

    /**
     * V309：标记已付并写入付款留痕（操作人 / 凭证号 / 备注）。
     *
     * <p>原实现只写 {@code status} + {@code paidAt}，运营点一下就是「已付」——
     * <b>谁付的、凭什么付的在系统里查不到</b>。场地租金是对外付款，必须可审计。
     *
     * @param voucherNo 付款凭证号，可为 null（兼容既有调用方不强填）
     * @param remark     付款备注，可为 null
     */
    @Transactional
    public SiteRentBillDto markPaid(Long operatorId, Long billId, String voucherNo, String remark) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        SiteRentBill bill = requireBill(billId);
        if (CabinetConstants.FEE_BILL_STATUS_VOID.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已作废账单不可标记已付");
        }
        if (CabinetConstants.FEE_BILL_STATUS_PAID.equals(bill.getStatus())) {
            return toDto(bill);
        }
        Instant now = Instant.now();
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_PAID);
        bill.setPaidAt(now);
        // V309：留痕。审计日志里也带上凭证号 —— 只落业务表不落审计日志的话，
        // 将来若有人质疑「凭证号是不是事后补的」，无法自证先后顺序。
        bill.setPaidBy(operatorId);
        bill.setPaidVoucherNo(blankToNull(voucherNo));
        bill.setPaidRemark(blankToNull(remark));
        bill.setUpdatedAt(now);
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "SITE_RENT_BILL_PAID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth() + " amount=" + bill.getAmountCents()
                        + " voucherNo=" + bill.getPaidVoucherNo());
        return toDto(bill);
    }

    /** 兼容旧签名（无凭证号）：委托到带留痕的重载。 */
    @Transactional
    public SiteRentBillDto markPaid(Long operatorId, Long billId) {
        return markPaid(operatorId, billId, null, null);
    }

    @Transactional
    public SiteRentBillDto voidBill(Long operatorId, Long billId) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        SiteRentBill bill = requireBill(billId);
        if (CabinetConstants.FEE_BILL_STATUS_PAID.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已付账单不可作废");
        }
        if (CabinetConstants.FEE_BILL_STATUS_VOID.equals(bill.getStatus())) {
            return toDto(bill);
        }
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_VOID);
        bill.setUpdatedAt(Instant.now());
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "SITE_RENT_BILL_VOID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth());
        return toDto(bill);
    }

    private List<SiteRentBillDto> doGenerateForContract(Long operatorId, Long contractId, String month) {
        if (operatorId != null) {
            permissionService.requirePermission(operatorId, "ops:org:edit");
        }
        SiteContract contract = contractMapper.findByIdForUpdate(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "场地合同不存在"));
        if (billMapper.countNonVoidByContractAndMonth(contractId, month) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该合同 " + month + " 账期已出账");
        }

        int baseFee = Math.max(0, contract.getMonthlyFeeCents());
        YearMonth ym = YearMonth.parse(month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        List<SiteRentSplitRule> rules = ruleMapper.findByContractId(contractId).stream()
                .filter(r -> CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(r.getStatus()))
                .filter(r -> isEffectiveInMonth(r, monthStart, monthEnd))
                .toList();

        List<AllocationLine> lines = allocate(baseFee, rules);
        Instant now = Instant.now();
        List<SiteRentBillDto> out = new ArrayList<>();
        for (AllocationLine line : lines) {
            SiteRentBill bill = new SiteRentBill();
            bill.setContractId(contractId);
            bill.setDeviceId(contract.getDeviceId());
            bill.setSiteName(contract.getSiteName());
            bill.setBillMonth(month);
            bill.setPartyType(line.partyType());
            bill.setPartyId(line.partyId());
            bill.setShareBps(line.shareBps());
            bill.setFixedCents(line.fixedCents());
            bill.setBaseFeeCents(baseFee);
            bill.setAmountCents(line.amountCents());
            bill.setStatus(CabinetConstants.FEE_BILL_STATUS_UNPAID);
            bill.setRemark("由月费×分账规则生成；标记已付不触发自动打款");
            bill.setCreatedAt(now);
            bill.setUpdatedAt(now);
            billMapper.insert(bill);
            out.add(toDto(bill));
        }
        if (operatorId != null) {
            auditService.appendLog(operatorId, "SITE_RENT_BILL_GEN", "CONTRACT", String.valueOf(contractId),
                    "month=" + month + " bills=" + out.size() + " base=" + baseFee);
        }
        log.info("site rent bill generated contractId={} month={} bills={} baseFeeCents={} operatorId={}",
                contractId, month, out.size(), baseFee, operatorId);
        return out;
    }

    /**
     * 场地月费按分账规则拆到各方（房东/平台/商户/加盟商/其他）。
     *
     * <p><b>分摊口径（2026-10-06 定案）</b>：先从 base 扣除各方 {@code fixedCents} 之和，
     * 剩余 {@code (base − Σfixed)} 再按 {@code shareBps} 拆分，余数补首条 ACTIVE 规则。
     * <b>不变式：Σ账单金额恒等于 base</b>（改造前是「份额切 base + 额外加 fixed」，总额可超月费）。
     *
     * <p><b>为什么选这个口径</b>（<b>已剔除一条错误依据，见下</b>）：
     * <ol>
     *   <li><b>产品文案</b>：{@code OrgSitesView} 分账规则弹窗写「按各方份额拆分合同月费，
     *       <b>并可叠加固定金额</b>」—— 说明「固定额」是合同层面独立于份额的一项；</li>
     *   <li><b>总额守恒更安全</b>：平台应付总额 = 月费，任何口径下「应付总额 &gt; 合同月费」
     *       都需要合同有对应条款支撑；恒等于 base 时<b>不必逐单核对合同附加费条款</b>，
     *       少一类资损来源（改动前Σ账单可超 base 90%，谁写规则谁负责，无第二道校验）。</li>
     * </ol>
     *
     * <p>🔴 <b>本注释曾写过一条错误依据，已删除</b>：原文写「业界通行做法是先扣后分
     * （持牌支付分账强制『总额恒等于订单金额』、百分比租金的固定部分就是 base 本身）」，
     * 并据此断言「接真前必须改造」。<b>该依据是跨域类比，不成立</b> ——
     * <ul>
     *   <li>支付分账的「总额恒等于订单金额」是<b>支付通道</b>的规则，与<b>场地租金</b>是不同域；
     *   <li>2026-10-06 复核：旧系统 easygo（同一产品线上一代）<b>没有场地租金分摊功能</b>
     *       （{@code grep 场地租金|房租|rentFee|siteRent} 零业务命中；{@code BillService:423-435}
     *       只有「销售额 − 退款 = 利润」单层账单）⇒ 本产品线无先例可循；</li>
     *   <li>同业柜机运营方（友宝/丰宜/哈哈零兽）公开资料只有「<b>销售额</b>分成比例」
     *       （15%–35% / 20%–25% / 20%–30%），<b>从未出现「份额 + 固定额」双层结构</b>
     *       ⇒ 无同行先例支持「必须先扣后分」。</li>
     * </ul>
     * ⇒ 结论：口径选择应基于<b>本项目自身的产品语义与资损风险</b>（见上两条），
     * <b>不是</b>「业界主流」这四个字。<b>任何人再想改动此处，请先读这段。</b>
     *
     * <p><b>边界</b>：{@code Σfixed > base} 时剩余为负、无解 ⇒ 本方法显式抛 400，
     * 不静默产生负数或失衡账单。规则保存侧（{@code SiteRentSplitService.replaceRules}）
     * 只校验份额合计 100%，fixedCents 的上限由出账时本校验兜住。
     *
     * <p><b>迁移说明</b>：改动前 {@code site_rent_bill} / {@code site_rent_split_rule} 均无数据、
     * 无历史账单（2026-10-06 实测两表 0 行），故<b>无需数据迁移</b>。若后续已用旧口径出过账，
     * 改此方法即构成数据迁移问题（已出账金额需重算 + 差额说明），届时须先核对 {@code site_rent_bill} 存量。
     *
     * <p><b>UI 文案</b>：{@code OrgSitesView} 分账规则弹窗已同步为
     * 「先从场地月费扣除各方固定金额，剩余部分按份额拆分…<b>分账合计恒等于场地月费</b>」。
     */
    static List<AllocationLine> allocate(int baseFeeCents, List<SiteRentSplitRule> rules) {
        int base = Math.max(0, baseFeeCents);
        // H30(c)：仅 ACTIVE 规则参与分摊——INACTIVE 规则不占份额，余额也不会被并入首条 ACTIVE 规则
        List<SiteRentSplitRule> active = rules == null ? List.of() : rules.stream()
                .filter(SiteRentBillService::isActiveRule)
                .toList();
        if (active.isEmpty()) {
            return List.of(new AllocationLine(
                    CabinetConstants.RENT_PARTY_LANDLORD, null, CabinetConstants.SHARE_BPS_FULL, 0, base));
        }
        // 分摊口径（2026-10-06 定案，「先扣固定额，剩余再按份额分」）：
        //   1) 先从 base 扣除各方 fixedCents 之和——fixed 是「该方固定酬劳」，不从份额里出；
        //   2) 剩余 (base − Σfixed) 按 shareBps 拆分，余数补首条 ACTIVE 规则（保证 Σ份额 == 剩余）；
        //   3) 每方账单 = fixedCents + 份额部分。
        // 不变式：Σ账单金额恒等于 base（不再出现「总额超月费」）。
        // 边界：Σfixed > base 时剩余为负、无解 ⇒ 显式 400 而非静默产生负数账单。
        // 🔴 「依据」见本方法 javadoc：不要再写成「对齐业界通行做法」—— 该依据已核实为跨域类比
        //    （旧系统无此功能、同行无「份额+固定额」双层结构），口径选择依据是产品语义与资损风险。
        long sumFixed = 0;
        for (SiteRentSplitRule r : active) {
            sumFixed += Math.max(0, r.getFixedCents());
        }
        if (sumFixed > base) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "固定金额合计 " + sumFixed + " 分超过场地月费 " + base
                            + " 分，无法分摊（固定金额合计须 ≤ 场地月费）");
        }
        long remainAfterFixed = base - sumFixed;

        long allocated = 0;
        long[] shareParts = new long[active.size()];
        for (int i = 0; i < active.size(); i++) {
            SiteRentSplitRule r = active.get(i);
            shareParts[i] = remainAfterFixed * Math.max(0, r.getShareBps()) / CabinetConstants.SHARE_BPS_FULL;
            allocated += shareParts[i];
        }
        long rem = remainAfterFixed - allocated;
        if (!active.isEmpty()) {
            shareParts[0] += rem;
        }
        List<AllocationLine> lines = new ArrayList<>(active.size());
        for (int i = 0; i < active.size(); i++) {
            SiteRentSplitRule r = active.get(i);
            int fixed = Math.max(0, r.getFixedCents());
            int amount = Math.toIntExact(shareParts[i] + fixed);
            lines.add(new AllocationLine(
                    r.getPartyType(),
                    blankToNull(r.getPartyId()),
                    Math.max(0, r.getShareBps()),
                    fixed,
                    amount));
        }
        return lines;
    }

    /** H30(c)：status 为空按 ACTIVE（与保存时的默认一致），仅 ACTIVE 参与 10000bps 校验与分摊。 */
    static boolean isActiveRule(SiteRentSplitRule r) {
        return r != null && (r.getStatus() == null || r.getStatus().isBlank()
                || CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(r.getStatus().trim()));
    }

    static boolean isEffectiveInMonth(SiteRentSplitRule r, LocalDate monthStart, LocalDate monthEnd) {
        if (r.getEffectiveFrom() != null && r.getEffectiveFrom().isAfter(monthEnd)) {
            return false;
        }
        if (r.getEffectiveTo() != null && r.getEffectiveTo().isBefore(monthStart)) {
            return false;
        }
        return true;
    }

    private SiteContract requireContract(Long contractId) {
        return contractMapper.findById(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "场地合同不存在"));
    }

    private SiteRentBill requireBill(Long billId) {
        // H30(a)：行锁重查，markPaid/void 与重出账/并发状态变更互斥
        SiteRentBill bill = billMapper.selectByIdForUpdate(billId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "租金账单不存在"));
        return bill;
    }

    private SiteRentBillDto toDto(SiteRentBill b) {
        return new SiteRentBillDto(
                b.getBillId(), b.getContractId(), b.getDeviceId(), b.getSiteName(), b.getBillMonth(),
                b.getPartyType(), b.getPartyId(), b.getShareBps(), b.getFixedCents(),
                b.getBaseFeeCents(), b.getAmountCents(), b.getStatus(),
                b.getPaidAt(), b.getPaidBy(), b.getPaidVoucherNo(), b.getPaidRemark(),
                b.getRemark(), b.getCreatedAt(), b.getUpdatedAt());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String billLockKey(Long contractId, String billMonth) {
        return "site-rent-bill:" + contractId + ":" + billMonth;
    }

    private <T> T runWithBillLock(Long contractId, String billMonth, java.util.function.Supplier<T> action) {
        String key = billLockKey(contractId, billMonth);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租金账单生成中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    record AllocationLine(String partyType, String partyId, int shareBps, int fixedCents, int amountCents) {}
}
