package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ProfitSharingStatusDto;
import com.aicabinet.common.dto.RevenueSplitDto;
import com.aicabinet.common.dto.SubmitProfitSharingRequest;
import com.aicabinet.common.dto.UpsertMerchantRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MerchantService {
    private static final String PERM_OPS_MERCHANT_SPLIT = "ops:merchant:split";
    private static final String ORDERID = "orderId=";
    private static final String SPLIT = "SPLIT";


    private static final int EXPORT_LIMIT = 5000;

    private final MerchantMapper merchantRepository;
    private final DeviceInfoMapper deviceRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final MerchantScopeService merchantScopeService;
    private final WeChatProfitSharingService profitSharingService;
    private final RevenueSplitService revenueSplitService;
    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final DistributedLockService distributedLockService;

    public MerchantService(MerchantMapper merchantRepository,
                           DeviceInfoMapper deviceRepository,
                           OrderRevenueSplitMapper splitRepository,
                           PermissionService permissionService,
                           AdminAuditService auditService,
                           MerchantScopeService merchantScopeService,
                           WeChatProfitSharingService profitSharingService,
                           RevenueSplitService revenueSplitService,
                           ProfitSharingProperties profitSharingProperties,
                           WeChatPayProperties weChatPayProperties,
                           DistributedLockService distributedLockService) {
        this.merchantRepository = merchantRepository;
        this.deviceRepository = deviceRepository;
        this.splitRepository = splitRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.merchantScopeService = merchantScopeService;
        this.profitSharingService = profitSharingService;
        this.revenueSplitService = revenueSplitService;
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<MerchantDto> listMerchants(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:merchant:list");
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        Map<String, Long> deviceCounts = deviceRepository.findAll().stream()
                .filter(d -> d.getMerchantId() != null)
                .filter(d -> allowed == null || allowed.contains(d.getMerchantId()))
                .collect(Collectors.groupingBy(
                        com.aicabinet.trade.domain.DeviceInfo::getMerchantId,
                        Collectors.counting()));
        return merchantRepository.findAll().stream()
                .filter(m -> allowed == null || allowed.contains(m.getMerchantId()))
                .map(m -> toDto(m, deviceCounts.getOrDefault(m.getMerchantId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantDto> listMerchantsPage(Long operatorId, int page, int size, String keyword) {
        permissionService.requirePermission(operatorId, "ops:merchant:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        Collection<String> scopeFilter = allowed;
        Map<String, Long> deviceCounts = deviceRepository.findAll().stream()
                .filter(d -> d.getMerchantId() != null)
                .filter(d -> allowed == null || allowed.contains(d.getMerchantId()))
                .collect(Collectors.groupingBy(
                        com.aicabinet.trade.domain.DeviceInfo::getMerchantId,
                        Collectors.counting()));
        var result = merchantRepository.searchPage(scopeFilter, blankToNull(keyword), p, s);
        List<MerchantDto> items = result.getRecords().stream()
                .map(m -> toDto(m, deviceCounts.getOrDefault(m.getMerchantId(), 0L)))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Transactional
    public MerchantDto upsertMerchant(Long operatorId, UpsertMerchantRequest request) {
        String merchantId = request.merchantId().trim();
        return runWithMerchantLock(merchantId, () -> doUpsertMerchant(operatorId, request, merchantId));
    }

    private MerchantDto doUpsertMerchant(Long operatorId, UpsertMerchantRequest request, String merchantId) {
        permissionService.requirePermission(operatorId, "ops:merchant:edit");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId).orElse(new Merchant());
        boolean isNew = merchant.getMerchantId() == null;
        merchant.setMerchantId(merchantId);
        merchant.setMerchantName(request.merchantName().trim());
        merchant.setContactPhone(blankToNull(request.contactPhone()));
        merchant.setPlatformRateBps(request.platformRateBps() != null ? request.platformRateBps() : 1000);
        merchant.setWechatReceiverId(blankToNull(request.wechatReceiverId()));
        merchant.setStatus(request.status() != null && !request.status().isBlank()
                ? request.status().trim() : "ACTIVE");
        merchant.setRemark(blankToNull(request.remark()));
        if (request.allowMerchantPlanogramEdit() != null) {
            merchant.setAllowMerchantPlanogramEdit(request.allowMerchantPlanogramEdit());
        }
        if (request.allowMerchantPricingEdit() != null) {
            merchant.setAllowMerchantPricingEdit(request.allowMerchantPricingEdit());
        }
        if (request.packFieldEnabled() != null) {
            merchant.setPackFieldEnabled(request.packFieldEnabled());
        }
        if (request.packBizEnabled() != null) {
            merchant.setPackBizEnabled(request.packBizEnabled());
        }
        if (request.packTeamEnabled() != null) {
            merchant.setPackTeamEnabled(request.packTeamEnabled());
        }
        String parentId = blankToNull(request.parentMerchantId());
        if (parentId != null) {
            if (parentId.equals(merchantId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级商户不能是自己");
            }
            Merchant parent = merchantRepository.findById(parentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级商户不存在"));
            merchantScopeService.requireMerchantAccess(operatorId, parentId);
            // 禁止成环：parent 不能落在自己的下级树里
            Set<String> descendants = merchantScopeService.expandWithDescendants(Set.of(merchantId));
            if (descendants.contains(parentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级商户不能落在本节点下级");
            }
            merchant.setParentMerchantId(parent.getMerchantId());
        } else if (request.parentMerchantId() != null) {
            // 显式传空串：清空上级
            merchant.setParentMerchantId(null);
        }
        merchantRepository.save(merchant);
        auditService.appendLog(operatorId, isNew ? "MERCHANT_CREATE" : "MERCHANT_UPDATE",
                "MERCHANT", merchantId, merchant.getMerchantName());
        return toDto(merchant, deviceRepository.countByMerchantId(merchantId));
    }

    @Transactional(readOnly = true)
    public PageResult<RevenueSplitDto> listSplits(Long operatorId, int page, int size,
                                                   String merchantId, String status) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_SPLIT);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        if (merchantId != null && !merchantId.isBlank()) {
            merchantScopeService.requireMerchantAccess(operatorId, merchantId.trim());
        }
        List<String> statuses = resolveSplitStatuses(status);
        Page<OrderRevenueSplit> result = querySplits(allowed, merchantId, statuses, pageable);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> allowed == null || allowed.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        return new PageResult<>(
                result.getContent().stream()
                        .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public byte[] exportSplitsCsv(Long operatorId, String merchantId, String status) {
        permissionService.requirePermission(operatorId, "ops:merchant:export");
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        if (merchantId != null && !merchantId.isBlank()) {
            merchantScopeService.requireMerchantAccess(operatorId, merchantId.trim());
        }
        List<String> statuses = resolveSplitStatuses(status);
        Page<OrderRevenueSplit> page = querySplits(allowed, merchantId, statuses, pageable);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> allowed == null || allowed.contains(m.getMerchantId()))
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName));
        StringBuilder sb = new StringBuilder(
                "splitId,orderId,merchantId,merchantName,deviceId,grossCents,platformCents,merchantCents,status,createdAt\n");
        for (OrderRevenueSplit s : page.getContent()) {
            sb.append(csv(s.getSplitId())).append(',')
                    .append(csv(s.getOrderId())).append(',')
                    .append(csv(s.getMerchantId())).append(',')
                    .append(csv(merchantNames.get(s.getMerchantId()))).append(',')
                    .append(csv(s.getDeviceId())).append(',')
                    .append(s.getGrossCents()).append(',')
                    .append(s.getPlatformCents()).append(',')
                    .append(s.getMerchantCents()).append(',')
                    .append(csv(s.getStatus())).append(',')
                    .append(csv(String.valueOf(s.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public RevenueSplitDto submitWeChatProfitSharing(Long operatorId, String splitId,
                                                     SubmitProfitSharingRequest request) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_SPLIT);
        OrderRevenueSplit split = splitRepository.findById(splitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        return runWithOrderSplitLock(split.getOrderId(), () -> {
            OrderRevenueSplit locked = splitRepository.findByOrderIdForUpdate(split.getOrderId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
            merchantScopeService.requireMerchantAccess(operatorId, locked.getMerchantId());
            Merchant merchant = merchantRepository.findById(locked.getMerchantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
            String wxTxn = request != null ? request.wxTransactionId() : null;
            if (wxTxn == null || wxTxn.isBlank()) {
                wxTxn = locked.getWechatTransactionId();
            }
            if (wxTxn == null || wxTxn.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ApiMessages.INVALID_REQUEST + "：需提供 wxTransactionId（购物订单当前为余额支付）");
            }
            OrderRevenueSplit updated = profitSharingService.submitSplit(locked, merchant, wxTxn);
            auditService.appendLog(operatorId, "PROFIT_SHARING_SUBMIT", SPLIT, splitId,
                    ORDERID + locked.getOrderId() + " status=" + updated.getStatus());
            return toSplitDto(updated, merchant.getMerchantName());
        });
    }

    @Transactional
    public RevenueSplitDto refreshWeChatProfitSharing(Long operatorId, String splitId) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_SPLIT);
        OrderRevenueSplit split = splitRepository.findById(splitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        return runWithOrderSplitLock(split.getOrderId(), () -> {
            OrderRevenueSplit locked = splitRepository.findByOrderIdForUpdate(split.getOrderId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
            merchantScopeService.requireMerchantAccess(operatorId, locked.getMerchantId());
            OrderRevenueSplit updated = profitSharingService.refreshSplitStatus(locked);
            auditService.appendLog(operatorId, "PROFIT_SHARING_REFRESH", SPLIT, splitId,
                    ORDERID + locked.getOrderId() + " status=" + updated.getStatus());
            String merchantName = merchantRepository.findById(locked.getMerchantId())
                    .map(Merchant::getMerchantName)
                    .orElse(null);
            return toSplitDto(updated, merchantName);
        });
    }

    /**
     * 确认仅记账完结：无微信分账接收方时商户份额已入钱包，运营确认后不再占用「分账待跟进」。
     */
    @Transactional
    public RevenueSplitDto confirmLedgerOnly(Long operatorId, String splitId, String reason) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_SPLIT);
        OrderRevenueSplit split = splitRepository.findById(splitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        return runWithOrderSplitLock(split.getOrderId(), () -> {
            OrderRevenueSplit locked = splitRepository.findByOrderIdForUpdate(split.getOrderId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
            merchantScopeService.requireMerchantAccess(operatorId, locked.getMerchantId());
            OrderRevenueSplit updated;
            try {
                updated = revenueSplitService.confirmLedgerOnly(locked);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            String note = (reason == null || reason.isBlank()) ? "ledger-confirmed" : reason.trim();
            auditService.appendLog(operatorId, "SPLIT_LEDGER_CONFIRM", SPLIT, splitId,
                    ORDERID + locked.getOrderId() + "; reason=" + note);
            String merchantName = merchantRepository.findById(locked.getMerchantId())
                    .map(Merchant::getMerchantName)
                    .orElse(null);
            return toSplitDto(updated, merchantName);
        });
    }

    @Transactional(readOnly = true)
    public ProfitSharingStatusDto profitSharingStatus(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_SPLIT);
        boolean enabled = profitSharingProperties.enabled();
        boolean apiReady = profitSharingService.isApiReady();
        boolean mock = profitSharingService.isMockMode();
        String note;
        if (!enabled) {
            note = "分账未启用：设置 PROFIT_SHARING_ENABLED=true";
        } else if (mock) {
            note = "分账联调 Mock 已启用（不调用微信 API）；提交时填写任意 wxTransactionId 即可";
        } else if (!weChatPayProperties.isConfigured()) {
            note = "微信支付未配置完整，无法调用分账 API（或设 PROFIT_SHARING_MOCK_ENABLED=true 联调）";
        } else if (!apiReady) {
            note = "分账 API 未就绪";
        } else {
            note = "分账 API 就绪；余额支付订单需在提交时填写 wxTransactionId";
        }
        return new ProfitSharingStatusDto(
                enabled,
                apiReady,
                profitSharingProperties.retryEnabled(),
                profitSharingProperties.retryBatchSize(),
                wechatConfigLabel(mock, weChatPayProperties.isConfigured()),
                note
        );
    }

    private MerchantDto toDto(Merchant m, long deviceCount) {
        return new MerchantDto(
                m.getMerchantId(), m.getMerchantName(), m.getContactPhone(),
                m.getAlertContactName(), m.getAlertContactPhone(),
                m.getPlatformRateBps(), m.getWechatReceiverId(), m.getStatus(),
                m.getRemark(), deviceCount,
                m.isAllowMerchantPlanogramEdit(), m.isAllowMerchantPricingEdit(),
                m.isPackFieldEnabled(), m.isPackBizEnabled(), m.isPackTeamEnabled(),
                m.getParentMerchantId(),
                m.getCreatedAt(), m.getUpdatedAt());
    }

    private RevenueSplitDto toSplitDto(OrderRevenueSplit s, String merchantName) {
        String deviceName = null;
        if (s.getDeviceId() != null && !s.getDeviceId().isBlank()) {
            deviceName = deviceRepository.findById(s.getDeviceId())
                    .map(DeviceInfo::getDeviceName)
                    .orElse(null);
        }
        return new RevenueSplitDto(
                s.getSplitId(), s.getOrderId(), s.getMerchantId(), merchantName,
                s.getDeviceId(), s.getGrossCents(), s.getPlatformCents(),
                s.getMerchantCents(), s.getStatus(), s.getWechatOutOrderNo(),
                s.getWechatTransactionId(), s.getFailureReason(), s.getCreatedAt(),
                s.getSettlementBatchNo(), s.getSettleAfter(), s.getSettledAt(),
                deviceName);
    }

    private Page<OrderRevenueSplit> querySplits(Set<String> allowed, String merchantId,
                                                List<String> statuses, Pageable pageable) {
        if (allowed != null && allowed.isEmpty()) {
            return Page.empty(pageable);
        }
        String mid = merchantId != null && !merchantId.isBlank() ? merchantId.trim() : null;
        if (statuses != null && !statuses.isEmpty()) {
            if (mid != null) {
                return splitRepository.findByMerchantIdAndStatusInOrderByCreatedAtDesc(mid, statuses, pageable);
            }
            if (allowed != null) {
                return splitRepository.findByMerchantIdInAndStatusInOrderByCreatedAtDesc(allowed, statuses, pageable);
            }
            return splitRepository.findByStatusInOrderByCreatedAtDesc(statuses, pageable);
        }
        if (mid != null) {
            return splitRepository.findByMerchantIdOrderByCreatedAtDesc(mid, pageable);
        }
        if (allowed != null) {
            return splitRepository.findByMerchantIdInOrderByCreatedAtDesc(allowed, pageable);
        }
        return splitRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private static List<String> resolveSplitStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        String key = status.trim().toUpperCase();
        if ("PENDING".equals(key)) {
            return List.of("ACCRUED", "LEDGER_ONLY", "WECHAT_FAILED", "FAILED");
        }
        return List.of(key);
    }

    private static String csv(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    static String merchantLockKey(String merchantId) {
        return "merchant:" + merchantId;
    }

    private <T> T runWithMerchantLock(String merchantId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(merchantLockKey(merchantId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户资料处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(merchantLockKey(merchantId));
        }
    }

    private <T> T runWithOrderSplitLock(String orderId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(RevenueSplitService.orderSplitLockKey(orderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单分账处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(RevenueSplitService.orderSplitLockKey(orderId));
        }
    }

    private static String wechatConfigLabel(boolean mock, boolean configured) {
        if (mock) {
            return "MOCK";
        }
        return configured ? "CONFIGURED" : "MISSING";
    }
}
