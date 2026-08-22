package com.aicabinet.trade.service;

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

    private static final Set<String> CHANNELS = Set.of("WECHAT", "ALIPAY", "PAYSCORE");
    private static final Set<String> STATUSES = Set.of("DRAFT", "SUBMITTED", "ACTIVE", "REJECTED");

    private final MerchantPaymentOnboardingMapper onboardingMapper;
    private final MerchantMapper merchantMapper;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final AdminAuditService auditService;
    private final SecurityProperties securityProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final AlipayProperties alipayProperties;
    private final PayScoreProperties payScoreProperties;

    public MerchantOnboardingService(MerchantPaymentOnboardingMapper onboardingMapper,
                                     MerchantMapper merchantMapper,
                                     PermissionService permissionService,
                                     MerchantScopeService merchantScopeService,
                                     AdminAuditService auditService,
                                     SecurityProperties securityProperties,
                                     WeChatPayProperties weChatPayProperties,
                                     AlipayProperties alipayProperties,
                                     PayScoreProperties payScoreProperties) {
        this.onboardingMapper = onboardingMapper;
        this.merchantMapper = merchantMapper;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.alipayProperties = alipayProperties;
        this.payScoreProperties = payScoreProperties;
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
        permissionService.requirePermission(operatorId, "ops:merchant:onboard:edit");
        String mid = req.merchantId().trim();
        merchantScopeService.requireMerchantAccess(operatorId, mid);
        merchantMapper.findById(mid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在"));
        String channel = req.channel().trim().toUpperCase();
        if (!CHANNELS.contains(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel 仅支持 WECHAT/ALIPAY/PAYSCORE");
        }
        String status = req.status() == null || req.status().isBlank() ? "DRAFT" : req.status().trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 status");
        }

        MerchantPaymentOnboarding row;
        if (onboardingId != null) {
            row = onboardingMapper.selectById(onboardingId);
            if (row == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "进件记录不存在");
            }
        } else {
            row = onboardingMapper.findByMerchantAndChannel(mid, channel).orElseGet(MerchantPaymentOnboarding::new);
            if (row.getOnboardingId() == null) {
                row.setCreatedAt(Instant.now());
            }
        }
        row.setMerchantId(mid);
        row.setChannel(channel);
        row.setStatus(status);
        row.setExternalMchId(blankToNull(req.externalMchId()));
        row.setExternalRef(blankToNull(req.externalRef()));
        row.setNote(blankToNull(req.note()));
        row.setUpdatedAt(Instant.now());
        if ("ACTIVE".equals(status) || "SUBMITTED".equals(status)) {
            row.setLastSyncedAt(Instant.now());
        }
        if (row.getOnboardingId() == null) {
            onboardingMapper.insert(row);
        } else {
            onboardingMapper.updateById(row);
        }
        auditService.record(operatorId, "MERCHANT_ONBOARD_UPSERT", "MERCHANT", mid, channel + ":" + status);
        String name = merchantMapper.findById(mid).map(Merchant::getMerchantName).orElse(mid);
        return toDto(row, name);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> liveHints() {
        boolean mock = securityProperties.mockEnabled();
        return Map.of(
                "mockEnabled", mock,
                "wechatPayLive", weChatPayProperties.isConfigured() && !mock,
                "alipayPayLive", alipayProperties.isConfigured() && !mock,
                "payScoreLive", payScoreProperties.liveChargeEnabled() && !mock,
                "hint", mock ? "当前为 Mock/演示：进件状态仅留痕，不发起真实进件 API"
                        : "生产模式：进件状态供运营登记；真实 OpenAPI 可后续挂接"
        );
    }

    private MerchantPaymentOnboardingDto toDto(MerchantPaymentOnboarding o, String merchantName) {
        boolean live = switch (o.getChannel()) {
            case "WECHAT" -> weChatPayProperties.isConfigured() && !securityProperties.mockEnabled();
            case "ALIPAY" -> alipayProperties.isConfigured() && !securityProperties.mockEnabled();
            case "PAYSCORE" -> payScoreProperties.liveChargeEnabled() && !securityProperties.mockEnabled();
            default -> false;
        };
        return new MerchantPaymentOnboardingDto(
                o.getOnboardingId(), o.getMerchantId(), merchantName, o.getChannel(), o.getStatus(),
                o.getExternalMchId(), o.getExternalRef(), o.getNote(), o.getLastSyncedAt(),
                o.getCreatedAt(), o.getUpdatedAt(), live);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
