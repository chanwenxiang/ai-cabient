package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.MerchantPaymentOnboardingDto;
import com.aicabinet.common.dto.UpsertMerchantOnboardingRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantPaymentOnboarding;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantPaymentOnboardingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MerchantOnboardingService {
    private static final String MERCHANT_ONBOARD_REVIEW = "MERCHANT_ONBOARD_REVIEW";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String MERCHANT = "MERCHANT";
    private static final String PAYSCORE = "PAYSCORE";
    private static final String STATUS_REJECTED = "REJECTED";


    private static final String BIZ_MERCHANT_ONBOARD = "MERCHANT_ONBOARD";
    private static final Set<String> CHANNELS = Set.of(CabinetConstants.PAY_CHANNEL_WECHAT, CabinetConstants.PAY_CHANNEL_ALIPAY, PAYSCORE);
    private static final Set<String> STATUSES = Set.of(CabinetConstants.PROMOTION_STATUS_DRAFT, SUBMITTED, CabinetConstants.PROMOTION_STATUS_ACTIVE, STATUS_REJECTED);

    private final MerchantPaymentOnboardingMapper onboardingMapper;
    private final MerchantMapper merchantMapper;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final AdminAuditService auditService;
    private final SecurityProperties securityProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final AlipayProperties alipayProperties;
    private final PayScoreProperties payScoreProperties;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;

    public MerchantOnboardingService(MerchantPaymentOnboardingMapper onboardingMapper,
                                     MerchantMapper merchantMapper,
                                     PermissionService permissionService,
                                     MerchantScopeService merchantScopeService,
                                     AdminAuditService auditService,
                                     SecurityProperties securityProperties,
                                     WeChatPayProperties weChatPayProperties,
                                     AlipayProperties alipayProperties,
                                     PayScoreProperties payScoreProperties,
                                     DistributedLockService distributedLockService,
                                     ApprovalWorkflowService approvalWorkflowService) {
        this.onboardingMapper = onboardingMapper;
        this.merchantMapper = merchantMapper;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.alipayProperties = alipayProperties;
        this.payScoreProperties = payScoreProperties;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
    }

    @Transactional(readOnly = true)
    public List<MerchantPaymentOnboardingDto> list(Long operatorId, String merchantId, String channel, String status) {
        permissionService.requireAnyPermission(operatorId, "ops:merchant:onboard:list", "ops:merchant:list");
        Map<String, String> names = merchantMapper.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
        return onboardingMapper.search(merchantId, channel, status).stream()
                .filter(o -> {
                    try {
                        merchantScopeService.requireMerchantAccess(operatorId, o.getMerchantId());
                        return true;
                    } catch (ResponseStatusException e) {
                        return false;
                    }
                })
                .map(o -> toDto(o, names.get(o.getMerchantId())))
                .toList();
    }

    @Transactional
    public MerchantPaymentOnboardingDto upsert(Long operatorId, Long onboardingId, UpsertMerchantOnboardingRequest req) {
        String mid = req.merchantId().trim();
        String channel = req.channel().trim().toUpperCase();
        return runWithOnboardingLock(mid, channel, () -> doUpsert(operatorId, onboardingId, req, mid, channel));
    }

    @Transactional
    public MerchantPaymentOnboardingDto review(Long operatorId, long onboardingId, boolean approve, String remark) {
        permissionService.requireAnyPermission(operatorId, "ops:merchant:onboard:list", "ops:merchant:onboard:edit");
        MerchantPaymentOnboarding row = onboardingMapper.findByIdForUpdate(onboardingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "进件记录不存�?));
        merchantScopeService.requireMerchantAccess(operatorId, row.getMerchantId());
        return runWithOnboardingLock(row.getMerchantId(), row.getChannel(),
                () -> doReview(operatorId, row, approve, remark));
    }

    private MerchantPaymentOnboardingDto doReview(Long operatorId, MerchantPaymentOnboarding row,
                                                  boolean approve, String remark) {
        if (!SUBMITTED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已提交进件可审�?);
        }
        String bizId = String.valueOf(row.getOnboardingId());
        if (approvalWorkflowService.instanceStatus(BIZ_MERCHANT_ONBOARD, bizId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批流尚未发起，请重新提交进�?);
        }
        if (!approve) {
            approvalWorkflowService.completeRejected(operatorId, BIZ_MERCHANT_ONBOARD, bizId, trim(remark));
            row.setStatus(STATUS_REJECTED);
            row.setUpdatedAt(Instant.now());
            onboardingMapper.updateById(row);
            auditService.appendLog(operatorId, MERCHANT_ONBOARD_REVIEW, MERCHANT,
                    row.getMerchantId(), row.getChannel() + ":REJECTED");
            return toDto(row, merchantName(row.getMerchantId()));
        }
        approvalWorkflowService.completeApproved(operatorId, BIZ_MERCHANT_ONBOARD, bizId, trim(remark));
        if (approvalWorkflowService.isInstanceApproved(BIZ_MERCHANT_ONBOARD, bizId)) {
            row.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
            row.setLastSyncedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            onboardingMapper.updateById(row);
            auditService.appendLog(operatorId, MERCHANT_ONBOARD_REVIEW, MERCHANT,
                    row.getMerchantId(), row.getChannel() + ":ACTIVE");
        } else {
            auditService.appendLog(operatorId, MERCHANT_ONBOARD_REVIEW, MERCHANT,
                    row.getMerchantId(), row.getChannel() + ":NODE_APPROVED");
        }
        return toDto(row, merchantName(row.getMerchantId()));
    }

    private MerchantPaymentOnboardingDto doUpsert(Long operatorId, Long onboardingId,
                                                  UpsertMerchantOnboardingRequest req,
                                                  String mid, String channel) {
        permissionService.requirePermission(operatorId, "ops:merchant:onboard:edit");
        merchantScopeService.requireMerchantAccess(operatorId, mid);
        merchantMapper.findById(mid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存�?));
        if (!CHANNELS.contains(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel 仅支�?WECHAT/ALIPAY/PAYSCORE");
        }
        String status = normalizeOnboardingStatus(req.status());
        MerchantPaymentOnboarding row = loadOrCreateOnboarding(onboardingId, mid, channel);
        String previousStatus = row.getStatus();
        assertUpsertStatusTransition(previousStatus, status, row.getOnboardingId());
        applyOnboardingFields(row, mid, channel, status, req);
        if (row.getOnboardingId() == null) {
            onboardingMapper.insert(row);
        } else {
            onboardingMapper.updateById(row);
        }
        maybeStartOnboardingApproval(operatorId, mid, channel, status, previousStatus, row);
        auditService.appendLog(operatorId, "MERCHANT_ONBOARD_UPSERT", MERCHANT, mid, channel + ":" + status);
        return toDto(row, merchantName(mid));
    }

    private static String normalizeOnboardingStatus(String rawStatus) {
        String status = rawStatus == null || rawStatus.isBlank()
                ? CabinetConstants.PROMOTION_STATUS_DRAFT
                : rawStatus.trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 status");
        }
        return status;
    }

    private MerchantPaymentOnboarding loadOrCreateOnboarding(Long onboardingId, String mid, String channel) {
        if (onboardingId != null) {
            return onboardingMapper.findByIdForUpdate(onboardingId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "进件记录不存�?));
        }
        MerchantPaymentOnboarding row = onboardingMapper.findByMerchantAndChannelForUpdate(mid, channel)
                .orElseGet(MerchantPaymentOnboarding::new);
        if (row.getOnboardingId() == null) {
            row.setCreatedAt(Instant.now());
        }
        return row;
    }

    private void assertUpsertStatusTransition(String previousStatus, String status, Long onboardingId) {
        if (SUBMITTED.equals(previousStatus) && !SUBMITTED.equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批中的进件请通过审批操作处理");
        }
        boolean approvalEnabled = approvalWorkflowService.isDefinitionEnabled(BIZ_MERCHANT_ONBOARD);
        if (CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(status) && approvalEnabled) {
            String bizId = onboardingId == null ? null : String.valueOf(onboardingId);
            if (bizId == null || !approvalWorkflowService.isInstanceApproved(BIZ_MERCHANT_ONBOARD, bizId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "进件须审批通过后方可生�?);
            }
        }
    }

    private static void applyOnboardingFields(MerchantPaymentOnboarding row, String mid, String channel,
                                              String status, UpsertMerchantOnboardingRequest req) {
        row.setMerchantId(mid);
        row.setChannel(channel);
        row.setStatus(status);
        row.setExternalMchId(blankToNull(req.externalMchId()));
        row.setExternalRef(blankToNull(req.externalRef()));
        row.setNote(blankToNull(req.note()));
        row.setUpdatedAt(Instant.now());
        if (CabinetConstants.PROMOTION_STATUS_ACTIVE.equals(status) || SUBMITTED.equals(status)) {
            row.setLastSyncedAt(Instant.now());
        }
    }

    private void maybeStartOnboardingApproval(Long operatorId, String mid, String channel,
                                              String status, String previousStatus,
                                              MerchantPaymentOnboarding row) {
        if (!SUBMITTED.equals(status)) {
            return;
        }
        String bizId = String.valueOf(row.getOnboardingId());
        String approvalStatus = approvalWorkflowService.instanceStatus(BIZ_MERCHANT_ONBOARD, bizId).orElse(null);
        boolean canRestart = previousStatus == null
                || CabinetConstants.PROMOTION_STATUS_DRAFT.equals(previousStatus)
                || STATUS_REJECTED.equals(previousStatus)
                || (SUBMITTED.equals(previousStatus) && approvalStatus == null);
        if (canRestart && !"PENDING".equals(approvalStatus) && !"APPROVED".equals(approvalStatus)) {
            String merchantName = merchantName(mid);
            approvalWorkflowService.start(
                    BIZ_MERCHANT_ONBOARD,
                    bizId,
                    operatorId,
                    "商户进件 " + merchantName + " · " + channelLabel(channel));
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> liveHints() {
        boolean mock = securityProperties.mockEnabled();
        return Map.of(
                "mockEnabled", mock,
                "wechatPayLive", weChatPayProperties.isConfigured() && !mock,
                "alipayPayLive", alipayProperties.isConfigured() && !mock,
                "payScoreLive", payScoreProperties.liveChargeEnabled() && !mock,
                "hint", mock ? "当前�?Mock/演示：进件状态仅留痕，不发起真实进件 API"
                        : "生产模式：进件状态供运营登记；真�?OpenAPI 可后续挂�?
        );
    }

    private MerchantPaymentOnboardingDto toDto(MerchantPaymentOnboarding o, String merchantName) {
        boolean live = switch (o.getChannel()) {
            case CabinetConstants.PAY_CHANNEL_WECHAT -> weChatPayProperties.isConfigured() && !securityProperties.mockEnabled();
            case CabinetConstants.PAY_CHANNEL_ALIPAY -> alipayProperties.isConfigured() && !securityProperties.mockEnabled();
            case PAYSCORE -> payScoreProperties.liveChargeEnabled() && !securityProperties.mockEnabled();
            default -> false;
        };
        String approvalStatus = o.getOnboardingId() == null ? null
                : approvalWorkflowService.instanceStatus(BIZ_MERCHANT_ONBOARD, String.valueOf(o.getOnboardingId()))
                .orElse(null);
        return new MerchantPaymentOnboardingDto(
                o.getOnboardingId(), o.getMerchantId(), merchantName, o.getChannel(), o.getStatus(),
                o.getExternalMchId(), o.getExternalRef(), o.getNote(), o.getLastSyncedAt(),
                o.getCreatedAt(), o.getUpdatedAt(), live, approvalStatus);
    }

    private String merchantName(String merchantId) {
        return merchantMapper.findById(merchantId).map(Merchant::getMerchantName).orElse(merchantId);
    }

    private static String channelLabel(String channel) {
        return switch (channel) {
            case CabinetConstants.PAY_CHANNEL_WECHAT -> "微信";
            case CabinetConstants.PAY_CHANNEL_ALIPAY -> "支付�?;
            case PAYSCORE -> "支付�?;
            default -> channel;
        };
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String trim(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    static String onboardingLockKey(String merchantId, String channel) {
        return "merchant:onboarding:" + merchantId + ":" + channel;
    }

    private <T> T runWithOnboardingLock(String merchantId, String channel,
                                        java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(onboardingLockKey(merchantId, channel), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户进件处理中，请稍后重�?);
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(onboardingLockKey(merchantId, channel));
        }
    }
}
