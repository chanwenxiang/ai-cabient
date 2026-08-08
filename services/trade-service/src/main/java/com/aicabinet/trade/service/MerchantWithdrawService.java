package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWalletAccountDto;
import com.aicabinet.common.dto.MerchantWalletLedgerDto;
import com.aicabinet.common.dto.MerchantWalletOverviewDto;
import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWalletLedger;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MerchantWithdrawService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantWalletAccountMapper accountMapper;
    private final MerchantWalletLedgerMapper ledgerMapper;
    private final MerchantWalletService merchantWalletService;
    private final MerchantWithdrawPayoutService payoutService;
    private final MerchantWithdrawProperties properties;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public MerchantWithdrawService(MerchantWithdrawRequestMapper withdrawMapper,
                                   MerchantMapper merchantMapper,
                                   MerchantWalletAccountMapper accountMapper,
                                   MerchantWalletLedgerMapper ledgerMapper,
                                   MerchantWalletService merchantWalletService,
                                   MerchantWithdrawPayoutService payoutService,
                                   MerchantWithdrawProperties properties,
                                   MerchantFeaturePackService merchantFeaturePackService,
                                   PermissionService permissionService,
                                   AdminAuditService auditService) {
        this.withdrawMapper = withdrawMapper;
        this.merchantMapper = merchantMapper;
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.merchantWalletService = merchantWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantWalletAccountDto> listAccounts(Long operatorId, String keyword, int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                "ops:merchant-withdraw:list", "ops:finance:view", "ops:merchant:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<Merchant> q = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(Merchant::getMerchantId, kw)
                    .or().like(Merchant::getMerchantName, kw)
                    .or().like(Merchant::getContactPhone, kw));
        }
        q.orderByAsc(Merchant::getMerchantId);
        Page<Merchant> result = merchantMapper.selectPage(new Page<>(p + 1L, s), q);
        List<MerchantWalletAccountDto> items = result.getRecords().stream().map(this::toAccountDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public List<MerchantWalletLedgerDto> ledgers(Long operatorId, String merchantId, int limit) {
        permissionService.requireAnyPermission(operatorId,
                "ops:merchant-withdraw:list", "ops:finance:view", "ops:merchant:list");
        requireMerchant(merchantId);
        return ledgerMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, limit).stream()
                .map(this::toLedgerDto)
                .toList();
    }

    @Transactional
    public MerchantWalletAccountDto adjust(Long operatorId, String merchantId, long amountCents, String remark) {
        permissionService.requirePermission(operatorId, "ops:merchant-withdraw:adjust");
        requireMerchant(merchantId);
        if (amountCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调账金额不能为 0");
        }
        String refId = "ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String note = remark == null || remark.isBlank() ? "运营调账" : remark.trim();
        if (amountCents > 0) {
            merchantWalletService.credit(merchantId, amountCents, "ADJUST", "OPS_ADJUST", refId, note);
        } else {
            merchantWalletService.debit(merchantId, -amountCents, "ADJUST", "OPS_ADJUST", refId, note);
        }
        auditService.record(operatorId, "MERCHANT_WALLET_ADJUST", "MERCHANT_WALLET", merchantId,
                "金额(分)=" + amountCents + "；备注=" + note);
        return toAccountDto(requireMerchant(merchantId));
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantWithdrawRequestDto> listWithdraws(
            Long operatorId, String status, String merchantId, int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                "ops:merchant-withdraw:list", "ops:merchant-withdraw:review", "ops:finance:view");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<MerchantWithdrawRequest> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            q.eq(MerchantWithdrawRequest::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (merchantId != null && !merchantId.isBlank()) {
            q.eq(MerchantWithdrawRequest::getMerchantId, merchantId.trim());
        }
        q.orderByDesc(MerchantWithdrawRequest::getCreatedAt);
        Page<MerchantWithdrawRequest> result = withdrawMapper.selectPage(new Page<>(p + 1L, s), q);
        List<MerchantWithdrawRequestDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public MerchantWithdrawRequestDto apply(String merchantId, long amountCents, String requestNo) {
        Merchant merchant = requireMerchant(merchantId);
        return createWithdraw(merchant, amountCents, requestNo);
    }

    @Transactional
    public MerchantWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        String merchantId = resolveMerchantId(userId);
        return apply(merchantId, amountCents, requestNo);
    }

    @Transactional(readOnly = true)
    public MerchantWalletOverviewDto merchantOverview(Long userId) {
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return new MerchantWalletOverviewDto(
                    false, null, null, null, null, null, List.of(), List.of());
        }
        String merchantId = merchantIds.stream().sorted().findFirst().orElse(null);
        Merchant merchant = merchantMapper.findById(merchantId).orElse(null);
        if (merchant == null) {
            return new MerchantWalletOverviewDto(
                    false, null, null, null, null, null, List.of(), List.of());
        }
        MerchantWalletAccount account = merchantWalletService.ensureAccount(merchantId);
        long balance = value(account.getBalanceCents());
        long frozen = value(account.getFrozenCents());
        return new MerchantWalletOverviewDto(
                true,
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                balance,
                frozen,
                balance - frozen,
                ledgerMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, 10).stream()
                        .map(this::toLedgerDto)
                        .toList(),
                withdrawMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, 10).stream()
                        .map(this::toDto)
                        .toList()
        );
    }

    @Transactional
    public MerchantWithdrawRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, "ops:merchant-withdraw:review");
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!"PENDING_REVIEW".equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可审核");
        }
        Instant now = Instant.now();
        request.setReviewerId(operatorId);
        request.setReviewRemark(trim(remark));
        request.setReviewedAt(now);
        request.setUpdatedAt(now);
        if (!approve) {
            request.setStatus("REJECTED");
            withdrawMapper.updateById(request);
            merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                    "WITHDRAW", String.valueOf(request.getRequestId()), "提现驳回释放");
            auditService.record(operatorId, "MERCHANT_WITHDRAW_REVIEW", "MERCHANT_WITHDRAW",
                    String.valueOf(requestId), "驳回；金额(分)=" + request.getAmountCents()
                            + "；备注=" + trim(remark));
            return toDto(request);
        }
        request.setStatus("APPROVED");
        withdrawMapper.updateById(request);
        auditService.record(operatorId, "MERCHANT_WITHDRAW_REVIEW", "MERCHANT_WITHDRAW",
                String.valueOf(requestId), "通过；金额(分)=" + request.getAmountCents()
                        + "；备注=" + trim(remark));
        return attemptPayout(request, operatorId);
    }

    @Transactional
    public MerchantWithdrawRequestDto payout(Long operatorId, long requestId) {
        permissionService.requirePermission(operatorId, "ops:merchant-withdraw:review");
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!Set.of("APPROVED", "FAILED").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        auditService.record(operatorId, "MERCHANT_WITHDRAW_PAYOUT", "MERCHANT_WITHDRAW",
                String.valueOf(requestId), "打款金额(分)=" + request.getAmountCents());
        return attemptPayout(request, operatorId);
    }

    private MerchantWithdrawRequestDto createWithdraw(Merchant merchant, long amountCents, String requestNo) {
        validateAmount(merchant.getMerchantId(), amountCents);
        String no = normalizeRequestNo(requestNo);
        var existing = withdrawMapper.findByRequestNo(no);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Instant now = Instant.now();
        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestNo(no);
        request.setMerchantId(merchant.getMerchantId());
        request.setAmountCents(amountCents);
        request.setPayChannel(properties.mockEnabled() ? "MOCK" : "WECHAT");
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= properties.reviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
            merchantWalletService.freezeForWithdraw(merchant.getMerchantId(), amountCents,
                    "WITHDRAW", String.valueOf(request.getRequestId()), "提现申请冻结");
            return toDto(request);
        }
        request.setStatus("APPROVED");
        request.setReviewRemark("低于审核阈值自动通过");
        request.setReviewedAt(now);
        withdrawMapper.insert(request);
        merchantWalletService.freezeForWithdraw(merchant.getMerchantId(), amountCents,
                "WITHDRAW", String.valueOf(request.getRequestId()), "提现申请冻结");
        return attemptPayout(request, null);
    }

    private MerchantWithdrawRequestDto attemptPayout(MerchantWithdrawRequest request, Long operatorId) {
        Merchant merchant = requireMerchant(request.getMerchantId());
        request.setStatus("PAYING");
        request.setUpdatedAt(Instant.now());
        withdrawMapper.updateById(request);

        MerchantWithdrawPayoutService.PayoutResult result = payoutService.payout(request, merchant);
        Instant now = Instant.now();
        request.setPayChannel(result.payChannel());
        request.setPayoutRef(result.payoutRef());
        request.setPayoutMessage(trim(result.message()));
        request.setUpdatedAt(now);
        if (result.success()) {
            request.setStatus("PAID");
            request.setPaidAt(now);
            withdrawMapper.updateById(request);
            merchantWalletService.consumeFrozen(request.getMerchantId(), request.getAmountCents(),
                    "WITHDRAW", String.valueOf(request.getRequestId()), "提现打款成功");
            return toDto(request);
        }
        request.setStatus("FAILED");
        withdrawMapper.updateById(request);
        return toDto(request);
    }

    private void validateAmount(String merchantId, long amountCents) {
        if (amountCents < properties.minAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "最低提现 " + (properties.minAmountCents() / 100.0) + " 元");
        }
        MerchantWalletAccount account = merchantWalletService.ensureAccount(merchantId);
        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());
        if (available < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");
        }
        Instant start = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long used = withdrawMapper.sumAmountByMerchantSince(merchantId, start);
        if (used + amountCents > properties.dailyLimitCents()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "超过单日提现限额");
        }
    }

    private String resolveMerchantId(Long userId) {
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "运营账号请走后台调账/代提现");
        }
        if (merchantIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未绑定商户");
        }
        return merchantIds.stream().sorted(Comparator.naturalOrder()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "未绑定商户"));
    }

    private Merchant requireMerchant(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户 ID 无效");
        }
        return merchantMapper.findById(merchantId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在"));
    }

    private MerchantWithdrawRequest requireRequest(long requestId) {
        return withdrawMapper.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提现单不存在"));
    }

    private MerchantWalletAccountDto toAccountDto(Merchant merchant) {
        MerchantWalletAccount account = accountMapper.selectById(merchant.getMerchantId());
        long balance = account == null ? 0L : value(account.getBalanceCents());
        long frozen = account == null ? 0L : value(account.getFrozenCents());
        return new MerchantWalletAccountDto(
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                merchant.getContactPhone(),
                merchant.getStatus(),
                balance,
                frozen,
                balance - frozen
        );
    }

    private MerchantWithdrawRequestDto toDto(MerchantWithdrawRequest request) {
        Merchant merchant = merchantMapper.findById(request.getMerchantId()).orElse(null);
        return new MerchantWithdrawRequestDto(
                request.getRequestId(),
                request.getRequestNo(),
                request.getMerchantId(),
                merchant == null ? null : merchant.getMerchantName(),
                request.getAmountCents(),
                request.getStatus(),
                request.getPayChannel(),
                request.getReviewerId(),
                request.getReviewRemark(),
                request.getReviewedAt(),
                request.getPayoutRef(),
                request.getPayoutMessage(),
                request.getPaidAt(),
                request.getCreatedAt(),
                request.getUpdatedAt()
        );
    }

    private MerchantWalletLedgerDto toLedgerDto(MerchantWalletLedger ledger) {
        return new MerchantWalletLedgerDto(
                ledger.getLedgerId(),
                ledger.getMerchantId(),
                ledger.getEntryType(),
                ledger.getAmountCents(),
                ledger.getBalanceAfter(),
                ledger.getFrozenAfter(),
                ledger.getRefType(),
                ledger.getRefId(),
                ledger.getRemark(),
                ledger.getCreatedAt()
        );
    }

    private static String normalizeRequestNo(String requestNo) {
        if (requestNo != null && !requestNo.isBlank()) {
            return requestNo.trim();
        }
        return "MW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
