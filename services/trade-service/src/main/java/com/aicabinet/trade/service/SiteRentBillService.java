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

    @Transactional
    public SiteRentBillDto markPaid(Long operatorId, Long billId) {
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
        bill.setUpdatedAt(now);
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "SITE_RENT_BILL_PAID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth() + " amount=" + bill.getAmountCents());
        return toDto(bill);
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

    static List<AllocationLine> allocate(int baseFeeCents, List<SiteRentSplitRule> rules) {
        int base = Math.max(0, baseFeeCents);
        if (rules == null || rules.isEmpty()) {
            return List.of(new AllocationLine(
                    CabinetConstants.RENT_PARTY_LANDLORD, null, CabinetConstants.SHARE_BPS_FULL, 0, base));
        }
        long allocated = 0;
        long[] shareParts = new long[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            SiteRentSplitRule r = rules.get(i);
            shareParts[i] = (long) base * Math.max(0, r.getShareBps()) / CabinetConstants.SHARE_BPS_FULL;
            allocated += shareParts[i];
        }
        long rem = base - allocated;
        if (!rules.isEmpty()) {
            shareParts[0] += rem;
        }
        List<AllocationLine> lines = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            SiteRentSplitRule r = rules.get(i);
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
        SiteRentBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租金账单不存在");
        }
        return bill;
    }

    private SiteRentBillDto toDto(SiteRentBill b) {
        return new SiteRentBillDto(
                b.getBillId(), b.getContractId(), b.getDeviceId(), b.getSiteName(), b.getBillMonth(),
                b.getPartyType(), b.getPartyId(), b.getShareBps(), b.getFixedCents(),
                b.getBaseFeeCents(), b.getAmountCents(), b.getStatus(),
                b.getPaidAt(), b.getRemark(), b.getCreatedAt(), b.getUpdatedAt());
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
