package com.aicabinet.trade.service;

import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.MerchantNotifyLog;
import com.aicabinet.trade.domain.MerchantSubscribePref;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantNotifyLogMapper;
import com.aicabinet.trade.mapper.MerchantSubscribePrefMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 柜机意外事件即时通知：离线 / 停售。
 * <p>设备级 Redis 冷却防刷；商户侧按订阅偏好 + 柜机权限推送微信订阅消息；运营侧走钉钉/企微 Webhook。</p>
 */
@Service
public class MerchantDeviceIncidentNotifyService {

    public static final String ALERT_DEVICE_OFFLINE = "DEVICE_OFFLINE";
    public static final String ALERT_SALES_LOCKED = "SALES_LOCKED";

    private static final Logger log = LoggerFactory.getLogger(MerchantDeviceIncidentNotifyService.class);
    private static final Set<String> INCIDENT_TYPES = Set.of(ALERT_DEVICE_OFFLINE, ALERT_SALES_LOCKED);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final String COOLDOWN_KEY_PREFIX = "aicabinet:notify:incident:";

    private final MerchantSubscribePrefMapper subscribePrefRepository;
    private final MerchantNotifyLogMapper notifyLogRepository;
    private final UserInfoMapper userInfoRepository;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final PermissionService permissionService;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final SystemConfigService systemConfigService;
    private final RedissonClient redissonClient;

    public MerchantDeviceIncidentNotifyService(MerchantSubscribePrefMapper subscribePrefRepository,
                                               MerchantNotifyLogMapper notifyLogRepository,
                                               UserInfoMapper userInfoRepository,
                                               MerchantFeaturePackService merchantFeaturePackService,
                                               PermissionService permissionService,
                                               WeChatMiniAppClient weChatMiniAppClient,
                                               WeChatMiniAppProperties weChatMiniAppProperties,
                                               OpsAlertDispatcher opsAlertDispatcher,
                                               SystemConfigService systemConfigService,
                                               RedissonClient redissonClient) {
        this.subscribePrefRepository = subscribePrefRepository;
        this.notifyLogRepository = notifyLogRepository;
        this.userInfoRepository = userInfoRepository;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.permissionService = permissionService;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.systemConfigService = systemConfigService;
        this.redissonClient = redissonClient;
    }

    /** 柜机离线即时通知（失败只打日志，不抛）。 */
    public int notifyDeviceOffline(String deviceId, String detail) {
        return notifyIncident(deviceId, ALERT_DEVICE_OFFLINE, "柜机离线", detail);
    }

    /** 柜机停售（营业锁机）即时通知（失败只打日志，不抛）。 */
    public int notifySalesLocked(String deviceId, String reason) {
        String detail = reason == null || reason.isBlank() ? "营业锁机停售" : reason.trim();
        return notifyIncident(deviceId, ALERT_SALES_LOCKED, "柜机停售", detail);
    }

    /**
     * @return 成功推送的商户用户数（不含运营 Webhook）
     */
    public int notifyIncident(String deviceId, String alertType, String title, String detail) {
        if (deviceId == null || deviceId.isBlank()) {
            return 0;
        }
        String type = alertType == null ? "" : alertType.trim().toUpperCase();
        if (!INCIDENT_TYPES.contains(type)) {
            log.warn("ignore unknown incident type={} device={}", type, deviceId);
            return 0;
        }
        String id = deviceId.trim();
        try {
            if (!tryAcquireDeviceCooldown(type, id)) {
                log.info("incident notify cooldown skip type={} device={}", type, id);
                return 0;
            }
            String body = (detail == null || detail.isBlank()) ? title : detail.trim();
            dispatchOpsAlert(type, title, id, body);
            return pushToSubscribedMerchants(id, type, title, body);
        } catch (Exception ex) {
            log.warn("incident notify failed type={} device={}", type, id, ex);
            return 0;
        }
    }

    private boolean tryAcquireDeviceCooldown(String alertType, String deviceId) {
        int minutes = systemConfigService.getInt(
                SystemConfigService.MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES, 30);
        if (minutes <= 0) {
            return true;
        }
        String key = COOLDOWN_KEY_PREFIX + alertType + ":" + deviceId;
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        return Boolean.TRUE.equals(bucket.setIfAbsent("1", Duration.ofMinutes(minutes)));
    }

    private void dispatchOpsAlert(String type, String title, String deviceId, String detail) {
        opsAlertDispatcher.send(
                type,
                "[" + title + "] " + deviceId,
                detail,
                Map.of("deviceId", deviceId, "alertType", type));
    }

    private int pushToSubscribedMerchants(String deviceId, String alertType, String title, String detail) {
        List<MerchantSubscribePref> prefs = subscribePrefRepository.findByAlertTypeAndEnabledTrue(alertType);
        if (prefs.isEmpty()) {
            return 0;
        }
        String summary = title + " " + deviceId + " " + truncate(detail, 40);
        String digest = sha256("INCIDENT:" + alertType + ":" + deviceId);
        int cooldownMinutes = Math.max(1, systemConfigService.getInt(
                SystemConfigService.MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES, 30));
        Instant since = Instant.now().minusSeconds(cooldownMinutes * 60L);
        String templateId = weChatMiniAppProperties.subscribeTemplateId();
        int sent = 0;
        for (MerchantSubscribePref pref : prefs) {
            Long userId = pref.getUserId();
            if (userId == null) {
                continue;
            }
            try {
                if (!permissionService.hasPermission(userId, "merchant:alerts:view")) {
                    continue;
                }
                if (!userCanAccessDevice(userId, deviceId)) {
                    continue;
                }
                UserInfo user = userInfoRepository.findById(userId).orElse(null);
                if (user == null || user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
                    continue;
                }
                if (notifyLogRepository.findFirstByUserIdAndDigestAndSentAtAfter(userId, digest, since).isPresent()) {
                    continue;
                }
                boolean ok = weChatMiniAppClient.sendSubscribeMessage(
                        user.getWxOpenId(),
                        templateId != null ? templateId : "mock-template",
                        weChatMiniAppProperties.resolveNotifyPage(),
                        Map.of(
                                "thing1", title,
                                "thing2", summary,
                                "time3", TIME_FMT.format(Instant.now())
                        ));
                if (ok) {
                    MerchantNotifyLog row = new MerchantNotifyLog();
                    row.setUserId(userId);
                    row.setDigest(digest);
                    row.setPayload(summary);
                    row.setSentAt(Instant.now());
                    notifyLogRepository.save(row);
                    sent++;
                }
            } catch (Exception ex) {
                log.warn("incident merchant push failed user={} device={} type={}",
                        userId, deviceId, alertType, ex);
            }
        }
        if (sent > 0) {
            log.info("incident merchant notify sent={} type={} device={}", sent, alertType, deviceId);
        }
        return sent;
    }

    private boolean userCanAccessDevice(Long userId, String deviceId) {
        try {
            Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                    userId, MerchantFeaturePacks.FIELD);
            if (allowed == null) {
                return true;
            }
            return allowed.contains(deviceId);
        } catch (Exception ex) {
            log.debug("device scope check failed user={} device={}", userId, deviceId, ex);
            return false;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
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
