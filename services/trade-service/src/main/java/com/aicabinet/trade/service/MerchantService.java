package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ProfitSharingStatusDto;
import com.aicabinet.common.dto.RevenueSplitDto;
import com.aicabinet.common.dto.SubmitProfitSharingRequest;
import com.aicabinet.common.dto.UpsertMerchantRequest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MerchantService {

    private static final int EXPORT_LIMIT = 5000;

    private final MerchantMapper merchantRepository;
    private final DeviceInfoMapper deviceRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final MerchantScopeService merchantScopeService;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;

    public MerchantService(MerchantMapper merchantRepository,
                           DeviceInfoMapper deviceRepository,
                           OrderRevenueSplitMapper splitRepository,
                           PermissionService permissionService,
                           AdminAuditService auditService,
                           MerchantScopeService merchantScopeService,
                           WeChatProfitSharingService profitSharingService,
                           ProfitSharingProperties profitSharingProperties,
                           WeChatPayProperties weChatPayProperties) {
        this.merchantRepository = merchantRepository;
        this.deviceRepository = deviceRepository;
        this.splitRepository = splitRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.merchantScopeService = merchantScopeService;
        this.profitSharingService = profitSharingService;
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
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

    @Transactional
    public MerchantDto upsertMerchant(Long operatorId, UpsertMerchantRequest request) {
        permissionService.requirePermission(operatorId, "ops:merchant:edit");
        String merchantId = request.merchantId().trim();
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        Merchant merchant = merchantRepository.findById(merchantId).orElse(new Merchant());
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
        merchantRepository.save(merchant);
        auditService.record(operatorId, isNew ? "MERCHANT_CREATE" : "MERCHANT_UPDATE",
                "MERCHANT", merchantId, merchant.getMerchantName());
        return toDto(merchant, deviceRepository.countByMerchantId(merchantId));
    }

    @Transactional(readOnly = true)
    public PageResult<RevenueSplitDto> listSplits(Long operatorId, int page, int size,
                                                   String merchantId, String status) {
        permissionService.requirePermission(operatorId, "ops:merchant:split");
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
        permissionService.requirePermission(operatorId, "ops:merchant:split");
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
        permissionService.requirePermission(operatorId, "ops:merchant:split");
        OrderRevenueSplit split = splitRepository.findById(splitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        merchantScopeService.requireMerchantAccess(operatorId, split.getMerchantId());
        Merchant merchant = merchantRepository.findById(split.getMerchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        String wxTxn = request != null ? request.wxTransactionId() : null;
        if (wxTxn == null || wxTxn.isBlank()) {
            wxTxn = split.getWechatTransactionId();
        }
        if (wxTxn == null || wxTxn.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ApiMessages.INVALID_REQUEST + "：需提供 wxTransactionId（购物订单当前为余额支付）");
        }
        OrderRevenueSplit updated = profitSharingService.submitSplit(split, merchant, wxTxn);
        auditService.record(operatorId, "PROFIT_SHARING_SUBMIT", "SPLIT", splitId,
                "orderId=" + split.getOrderId() + " status=" + updated.getStatus());
        String merchantName = merchant.getMerchantName();
        return toSplitDto(updated, merchantName);
    }

    @Transactional
    public RevenueSplitDto refreshWeChatProfitSharing(Long operatorId, String splitId) {
        permissionService.requirePermission(operatorId, "ops:merchant:split");
        OrderRevenueSplit split = splitRepository.findById(splitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        merchantScopeService.requireMerchantAccess(operatorId, split.getMerchantId());
        OrderRevenueSplit updated = profitSharingService.refreshSplitStatus(split);
        auditService.record(operatorId, "PROFIT_SHARING_REFRESH", "SPLIT", splitId,
                "orderId=" + split.getOrderId() + " status=" + updated.getStatus());
        String merchantName = merchantRepository.findById(split.getMerchantId())
                .map(Merchant::getMerchantName)
                .orElse(null);
        return toSplitDto(updated, merchantName);
    }

    @Transactional(readOnly = true)
    public ProfitSharingStatusDto profitSharingStatus(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:merchant:split");
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
                mock ? "MOCK" : (weChatPayProperties.isConfigured() ? "CONFIGURED" : "MISSING"),
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
                m.getCreatedAt(), m.getUpdatedAt());
    }

    private RevenueSplitDto toSplitDto(OrderRevenueSplit s, String merchantName) {
        return new RevenueSplitDto(
                s.getSplitId(), s.getOrderId(), s.getMerchantId(), merchantName,
                s.getDeviceId(), s.getGrossCents(), s.getPlatformCents(),
                s.getMerchantCents(), s.getStatus(), s.getWechatOutOrderNo(),
                s.getWechatTransactionId(), s.getFailureReason(), s.getCreatedAt(),
                s.getSettlementBatchNo(), s.getSettleAfter(), s.getSettledAt());
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
            return null;
        }
        String key = status.trim().toUpperCase();
        if ("PENDING".equals(key)) {
            return List.of("ACCRUED", "LEDGER_ONLY", "WECHAT_FAILED", "FAILED");
        }
        return List.of(key);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
}
