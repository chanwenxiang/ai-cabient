package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantNotifyPrefDto;
import com.aicabinet.common.dto.MerchantSubscribeRequest;
import com.aicabinet.common.dto.MerchantWorkbenchDto;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.MerchantNotifyLog;
import com.aicabinet.trade.domain.MerchantSubscribePref;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantNotifyLogMapper;
import com.aicabinet.trade.mapper.MerchantSubscribePrefMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MerchantNotifyService {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotifyService.class);
    private static final Set<String> ALLOWED_ALERT_TYPES = Set.of(
            "DISPUTE", "DEVICE_OFFLINE", "LOW_STOCK", "EXPIRY", "SLOT_DISCREPANCY", "REPLENISHMENT");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final MerchantPortalGuard merchantPortalGuard;
    private final PermissionService permissionService;
    private final MerchantPortalService merchantPortalService;
    private final UserInfoMapper userInfoRepository;
    private final MerchantSubscribePrefMapper subscribePrefRepository;
    private final MerchantNotifyLogMapper notifyLogRepository;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;

    public MerchantNotifyService(MerchantPortalGuard merchantPortalGuard,
                                 PermissionService permissionService,
                                 @Lazy MerchantPortalService merchantPortalService,
                                 UserInfoMapper userInfoRepository,
                                 MerchantSubscribePrefMapper subscribePrefRepository,
                                 MerchantNotifyLogMapper notifyLogRepository,
                                 WeChatMiniAppClient weChatMiniAppClient,
                                 WeChatMiniAppProperties weChatMiniAppProperties) {
        this.merchantPortalGuard = merchantPortalGuard;
        this.permissionService = permissionService;
        this.merchantPortalService = merchantPortalService;
        this.userInfoRepository = userInfoRepository;
        this.subscribePrefRepository = subscribePrefRepository;
        this.notifyLogRepository = notifyLogRepository;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
    }

    @Transactional(readOnly = true)
    public MerchantNotifyPrefDto getPrefs(Long userId) {
        merchantPortalGuard.requireAccess(userId);
        UserInfo user = requireUser(userId);
        List<String> enabled = subscribePrefRepository.findByIdUserIdAndEnabledTrue(userId).stream()
                .map(p -> p.getId().getAlertType())
                .sorted()
                .toList();
        return new MerchantNotifyPrefDto(user.getWxOpenId() != null && !user.getWxOpenId().isBlank(), enabled);
    }

    @Transactional
    public MerchantNotifyPrefDto bindWxOpenId(Long userId, String wxCode) {
        merchantPortalGuard.requireAccess(userId);
        UserInfo user = requireUser(userId);
        var session = weChatMiniAppClient.code2Session(wxCode);
        user.setWxOpenId(session.openId());
        userInfoRepository.save(user);
        return getPrefs(userId);
    }

    @Transactional
    public MerchantNotifyPrefDto updateSubscribe(Long userId, MerchantSubscribeRequest request) {
        merchantPortalGuard.requireAccess(userId);
        permissionService.requirePermission(userId, "merchant:alerts:view");
        List<String> types = request.alertTypes() != null ? request.alertTypes() : List.of();
        subscribePrefRepository.findByIdUserId(userId).forEach(p -> {
            p.setEnabled(false);
            subscribePrefRepository.save(p);
        });
        for (String raw : types) {
            String type = raw != null ? raw.trim().toUpperCase(Locale.ROOT) : "";
            if (!ALLOWED_ALERT_TYPES.contains(type)) {
                continue;
            }
            MerchantSubscribePref pref = subscribePrefRepository
                    .findById(new com.aicabinet.trade.domain.MerchantSubscribePrefId(userId, type))
                    .orElseGet(() -> new MerchantSubscribePref(userId, type));
            pref.setEnabled(true);
            subscribePrefRepository.save(pref);
        }
        return getPrefs(userId);
    }

    @Transactional
    public void dispatchWorkbenchAlerts() {
        List<Long> userIds = subscribePrefRepository.findAll().stream()
                .filter(MerchantSubscribePref::isEnabled)
                .map(p -> p.getId().getUserId())
                .distinct()
                .toList();
        for (Long userId : userIds) {
            try {
                maybeNotifyUser(userId);
            } catch (Exception ex) {
                log.warn("merchant notify failed user={}", userId, ex);
            }
        }
    }

    @Transactional
    public void maybeNotifyUser(Long userId) {
        UserInfo user = userInfoRepository.findById(userId).orElse(null);
        if (user == null || user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
            return;
        }
        if (!permissionService.hasPermission(userId, "merchant:alerts:view")) {
            return;
        }
        Set<String> enabledTypes = new HashSet<>(subscribePrefRepository.findByIdUserIdAndEnabledTrue(userId).stream()
                .map(p -> p.getId().getAlertType())
                .toList());
        if (enabledTypes.isEmpty()) {
            return;
        }
        MerchantWorkbenchDto wb = merchantPortalService.getWorkbench(userId);
        if (!shouldNotify(wb, enabledTypes)) {
            return;
        }
        String summary = buildSummary(wb, enabledTypes);
        String digest = sha256(summary);
        Instant since = Instant.now().minusSeconds(4 * 3600L);
        if (notifyLogRepository.findFirstByUserIdAndDigestAndSentAtAfter(userId, digest, since).isPresent()) {
            return;
        }
        String templateId = weChatMiniAppProperties.subscribeTemplateId();
        boolean sent = weChatMiniAppClient.sendSubscribeMessage(
                user.getWxOpenId(),
                templateId != null ? templateId : "mock-template",
                weChatMiniAppProperties.resolveNotifyPage(),
                Map.of(
                        "thing1", "商户运营待办",
                        "thing2", summary,
                        "time3", TIME_FMT.format(Instant.now())
                ));
        if (sent) {
            MerchantNotifyLog row = new MerchantNotifyLog();
            row.setUserId(userId);
            row.setDigest(digest);
            row.setPayload(summary);
            notifyLogRepository.save(row);
        }
    }

    private static boolean shouldNotify(MerchantWorkbenchDto wb, Set<String> enabledTypes) {
        if (enabledTypes.contains("DISPUTE") && wb.openDisputes() > 0) return true;
        if (enabledTypes.contains("DEVICE_OFFLINE") && wb.offlineDevices() > 0) return true;
        if (enabledTypes.contains("LOW_STOCK") && wb.lowStockItems() > 0) return true;
        if (enabledTypes.contains("EXPIRY") && wb.expiryAlerts() > 0) return true;
        return false;
    }

    private static String buildSummary(MerchantWorkbenchDto wb, Set<String> enabledTypes) {
        List<String> parts = new ArrayList<>();
        if (enabledTypes.contains("DISPUTE") && wb.openDisputes() > 0) {
            parts.add("争议" + wb.openDisputes());
        }
        if (enabledTypes.contains("DEVICE_OFFLINE") && wb.offlineDevices() > 0) {
            parts.add("离线" + wb.offlineDevices());
        }
        if (enabledTypes.contains("LOW_STOCK") && wb.lowStockItems() > 0) {
            parts.add("低库存" + wb.lowStockItems());
        }
        if (enabledTypes.contains("EXPIRY") && wb.expiryAlerts() > 0) {
            parts.add("效期" + wb.expiryAlerts());
        }
        return String.join(" ", parts);
    }

    private UserInfo requireUser(Long userId) {
        return userInfoRepository.findById(userId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
