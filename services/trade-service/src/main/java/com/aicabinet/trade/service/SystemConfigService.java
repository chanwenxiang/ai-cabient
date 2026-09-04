package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsBrandDto;
import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.mapper.SystemConfigMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemConfigService {
    private static final String FALSE = "false";


    public static final String CONSUMER_SERVICE_PHONE = "consumer.service_phone";
    public static final String OPS_SUPPORT_EMAIL = "ops.support_email";
    public static final String SETTLEMENT_MIN_CONFIDENCE = "settlement.min_confidence";
    public static final String DISPUTE_AUTO_OPEN = "dispute.auto_open";
    public static final String REFUND_DEFAULT_POLICY = "refund.default_policy";
    public static final String REFUND_SELF_MAX_HOURS = "refund.self.max_hours";
    public static final String REFUND_SELF_MAX_CENTS = "refund.self.max_cents";
    public static final String REFUND_SELF_MAX_DAILY = "refund.self.max_daily";
    public static final String REFUND_SELF_PARTIAL_ENABLED = "refund.self.partial_enabled";
    /** 待支付订单超时自动关单小时数, 0=关闭自动关单. */
    public static final String UNPAID_AUTO_CANCEL_HOURS = "order.unpaid.auto_cancel_hours";
    /** 超时关单时是否自动拉黑用户. */
    public static final String UNPAID_AUTO_BLACKLIST = "order.unpaid.auto_blacklist";
    /** 待支付充值单超时自动取消分钟数, 0=关闭. */
    public static final String RECHARGE_AUTO_CANCEL_MINUTES = "recharge.pending.auto_cancel_minutes";
    /** 设备离线超过该分钟数后自动锁机停售, 0=不自动锁机. */
    public static final String DEVICE_OFFLINE_AUTO_LOCK_MINUTES = "device.offline.auto_sales_lock_minutes";
    /** 人工/策略解锁后，离线自动锁机宽限分钟数, 0=无宽限. */
    public static final String DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES =
            "device.offline.manual_unlock_grace_minutes";
    public static final String DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED = "device.offline.auto_unlock_enabled";
    public static final String DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES = "device.offline.auto_unlock_stable_minutes";
    /** 柜机离线/停售即时通知冷却分钟数（同柜同类型），0=不冷却. */
    public static final String MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES =
            "merchant.notify.incident_cooldown_minutes";
    public static final String DISPUTE_SLA_HOURS = "dispute.sla.hours";
    public static final String DISPUTE_SLA_REMINDER_HOURS = "dispute.sla.reminder_hours";
    public static final String DISPUTE_SLA_WEBHOOK = "dispute.sla.webhook";
    public static final String OPS_ALERT_DINGTALK_WEBHOOK = "ops.alert.dingtalk_webhook";
    public static final String OPS_ALERT_WECOM_WEBHOOK = "ops.alert.wecom_webhook";
    public static final String OPS_ALERT_WEBHOOK = "ops.alert.webhook";
    public static final String OPS_SCAN_DOOR_OPEN_MINUTES = "ops.scan.door_open_minutes";
    public static final String OPS_SCAN_UPLOAD_STUCK_MINUTES = "ops.scan.upload_stuck_minutes";
    public static final String OPS_SCAN_RECOGNITION_STUCK_MINUTES = "ops.scan.recognition_stuck_minutes";
    public static final String OPS_SCAN_SETTLEMENT_STUCK_MINUTES = "ops.scan.settlement_stuck_minutes";
    /** 运营后台品牌标题（登录页主标题 / 浏览器标题前缀）。 */
    public static final String OPS_BRAND_TITLE = "ops.brand.title";
    /** 运营后台副标题（登录页副文案）。 */
    public static final String OPS_BRAND_SUBTITLE = "ops.brand.subtitle";
    /** 侧栏展开时品牌文案。 */
    public static final String OPS_BRAND_SIDEBAR_TITLE = "ops.brand.sidebar_title";
    /** 品牌 Logo URL（空则用标题首字占位）。 */
    public static final String OPS_BRAND_LOGO_URL = "ops.brand.logo_url";
    /** 消费者开门预授权冻结金额(分), 优先于配置文件, 柜机押金可覆盖. */
    public static final String CHECKOUT_PREAUTH_CENTS = "checkout.preauth_cents";
    /** 纯视觉柜（会话无重力字段）空车是否自动零结；默认 false 进争议。 */
    public static final String SETTLEMENT_EMPTY_AUTO_NO_GRAVITY =
            "settlement.empty_auto_complete_no_gravity";
    /** 结算识别方式: VISION=纯视觉；VISION_GRAVITY=视觉+重力融合。 */
    public static final String SETTLEMENT_RECOGNITION_MODE = "settlement.recognition_mode";
    public static final String RECOGNITION_MODE_VISION = "VISION";
    public static final String RECOGNITION_MODE_VISION_GRAVITY = "VISION_GRAVITY";

    private final SystemConfigMapper repository;
    private final SecurityProperties securityProperties;
    private final AlipayProperties alipayProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final PayScoreProperties payScoreProperties;
    private final WeChatWebProperties weChatWebProperties;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final QrProperties qrProperties;
    private final DistributedLockService distributedLockService;
    private final SystemConfigService self;

    public SystemConfigService(SystemConfigMapper repository,
                               SecurityProperties securityProperties,
                               AlipayProperties alipayProperties,
                               WeChatPayProperties weChatPayProperties,
                               PayScoreProperties payScoreProperties,
                               WeChatWebProperties weChatWebProperties,
                               WeChatMiniAppProperties weChatMiniAppProperties,
                               QrProperties qrProperties,
                               DistributedLockService distributedLockService,
                               @Lazy SystemConfigService self) {
        this.repository = repository;
        this.securityProperties = securityProperties;
        this.alipayProperties = alipayProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.payScoreProperties = payScoreProperties;
        this.weChatWebProperties = weChatWebProperties;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.qrProperties = qrProperties;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public String getValue(String key, String defaultValue) {
        return repository.findById(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    /** 是否在结算中融合重力（仅 VISION_GRAVITY）。默认 VISION=否。 */
    @Transactional(readOnly = true)
    public boolean usesGravityFusion() {
        String mode = self.getValue(SETTLEMENT_RECOGNITION_MODE, RECOGNITION_MODE_VISION);
        return RECOGNITION_MODE_VISION_GRAVITY.equalsIgnoreCase(mode.trim());
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        String raw = self.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = self.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    @Transactional(readOnly = true)
    public double getDouble(String key, double defaultValue) {
        String raw = self.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, String> consumerPublicConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("servicePhone", self.getValue(CONSUMER_SERVICE_PHONE, "400-888-0018"));
        map.put("supportEmail", self.getValue(OPS_SUPPORT_EMAIL, "ops@aicabinet.local"));
        boolean wechatSubscribeOk = weChatMiniAppProperties.isConfigured()
                && weChatMiniAppProperties.resolveConsumerTemplateId() != null
                && !weChatMiniAppProperties.resolveConsumerTemplateId().isBlank();
        map.put("wechatSubscribeEnabled", String.valueOf(wechatSubscribeOk));
        map.put("wechatSubscribeTemplateId",
                wechatSubscribeOk ? weChatMiniAppProperties.resolveConsumerTemplateId() : "");
        map.put("mockEnabled", String.valueOf(securityProperties.mockEnabled()));
        // 沙箱: 已配置支付宝密钥, 或 mock 模式下允许走 mock 支付宝预下单
        boolean alipayOk = alipayProperties.isConfigured()
                || (securityProperties.mockEnabled() && alipayProperties.enabled());
        map.put("alipayRechargeEnabled", String.valueOf(alipayOk));
        // 微信: 已完整配置走 live; 未配置但开启 mock 时允许微信模拟预下单+确认
        boolean wechatOk = weChatPayProperties.isConfigured() || securityProperties.mockEnabled();
        map.put("wechatRechargeEnabled", String.valueOf(wechatOk));
        map.put("wechatPayLive", String.valueOf(weChatPayProperties.isConfigured()));
        map.put("alipayPayLive", String.valueOf(alipayProperties.isConfigured()));
        // 支付分开门: 显式开启或 mock 时前端展示一键开通
        map.put("payScoreSignEnabled",
                String.valueOf(payScoreProperties.enabled() || securityProperties.mockEnabled()));
        map.put("refundDefaultPolicy", self.getValue(REFUND_DEFAULT_POLICY, "AUTO_REFUND"));
        map.put("paymentModeHint", securityProperties.mockEnabled()
                ? "模拟支付(无真实进件), 充值可一键到账, 订单退款退回余额"
                : "真实/沙箱支付");
        // H5 微信网页授权：已配置公众号密钥时下发真实 OAuth 跳转 URL；dev mock 下前端直连 wx-h5-login
        boolean webOauthConfigured = weChatWebProperties.isConfigured();
        boolean webOauthOk = webOauthConfigured
                || (securityProperties.mockEnabled() && weChatWebProperties.enabled());
        map.put("wechatH5OauthEnabled", String.valueOf(webOauthOk));
        if (webOauthConfigured) {
            map.put("wechatH5OauthUrl", buildWechatH5OauthUrl());
        }
        map.put("preauthCents", self.getValue(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS)));
        return map;
    }

    /** 运营后台品牌（登录页无需鉴权）。 */
    @Transactional(readOnly = true)
    public OpsBrandDto opsBrandPublic() {
        String title = self.getValue(OPS_BRAND_TITLE, "AI开门柜");
        String subtitle = self.getValue(OPS_BRAND_SUBTITLE, "运营管理系统");
        String sidebar = self.getValue(OPS_BRAND_SIDEBAR_TITLE, title + "运营");
        String logoUrl = self.getValue(OPS_BRAND_LOGO_URL, "");
        return new OpsBrandDto(title, subtitle, sidebar, logoUrl == null ? "" : logoUrl);
    }

    private String buildWechatH5OauthUrl() {
        String redirect = qrProperties.normalizedConsumerH5Base();
        try {
            return "https://open.weixin.qq.com/connect/oauth2/authorize"
                    + "?appid=" + java.net.URLEncoder.encode(weChatWebProperties.appId(),
                            java.nio.charset.StandardCharsets.UTF_8)
                    + "&redirect_uri=" + java.net.URLEncoder.encode(redirect,
                            java.nio.charset.StandardCharsets.UTF_8)
                    + "&response_type=code&scope=snsapi_base&state=wechat#wechat_redirect";
        } catch (Exception e) {
            return "";
        }
    }

    @Transactional
    public List<SystemConfigDto> listAll() {
        ensureDefaults();
        List<SystemConfigDto> out = new ArrayList<>();
        for (SystemConfig config : repository.findAll()) {
            out.add(toDto(config));
        }
        out.sort(Comparator.comparing(SystemConfigDto::configKey));
        return out;
    }

    @Transactional
    public SystemConfigDto upsert(UpsertSystemConfigRequest request) {
        return self.upsert(request.configKey(), request.configValue(), request.description());
    }

    @Transactional
    public SystemConfigDto upsert(String key, String value, String description) {
        return runWithConfigLock(key, () -> doUpsert(key, value, description));
    }

    private SystemConfigDto doUpsert(String key, String value, String description) {
        SystemConfig config = repository.findByIdForUpdate(key).orElseGet(SystemConfig::new);
        config.setConfigKey(key);
        config.setConfigValue(value == null ? "" : value);
        if (description != null && !description.isBlank()) {
            config.setDescription(description);
        } else if (config.getDescription() == null) {
            config.setDescription("");
        }
        config.setUpdatedAt(Instant.now());
        return toDto(repository.save(config));
    }

    @Transactional
    public void delete(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置键不能为空");
        }
        runWithConfigLock(configKey, () -> {
            if (repository.findByIdForUpdate(configKey).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "参数不存在");
            }
            repository.deleteById(configKey);
            return null;
        });
    }

    static String systemConfigLockKey(String configKey) {
        return "sys:config:" + configKey.trim();
    }

    private <T> T runWithConfigLock(String configKey, java.util.function.Supplier<T> action) {
        String key = systemConfigLockKey(configKey);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统配置处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private void ensureDefaults() {
        upsertIfAbsent(CONSUMER_SERVICE_PHONE, "400-888-0018", "C端客服电话");
        upsertIfAbsent(OPS_SUPPORT_EMAIL, "ops@aicabinet.local",
                "运营支持邮箱（下发 C 端公开配置 supportEmail）");
        upsertIfAbsent(SETTLEMENT_MIN_CONFIDENCE, "0.72",
                "自动结算最低整体识别置信度（低于则触发人工审；单品另看 SKU 扣款阈值）");
        upsertIfAbsent(SETTLEMENT_RECOGNITION_MODE, RECOGNITION_MODE_VISION,
                "结算识别方式: VISION=纯视觉(忽略重力), VISION_GRAVITY=视觉+重力融合");
        upsertIfAbsent(SETTLEMENT_EMPTY_AUTO_NO_GRAVITY, FALSE,
                "纯视觉柜空车是否自动零结（无重力字段时）；默认 false 进争议");
        upsertIfAbsent(DISPUTE_AUTO_OPEN, "true",
                "识别低置信是否自动开争议工单；false 时仍结算但记日志（空车/超时等安全路径不受影响）");
        upsertIfAbsent(REFUND_DEFAULT_POLICY, "AUTO_REFUND",
                "全局默认退款策略: AUTO_REFUND=自助退款, DISPUTE_ONLY=仅申诉");
        upsertIfAbsent(REFUND_SELF_MAX_HOURS, "24", "消费者自助退款时限（下单后小时数）");
        upsertIfAbsent(REFUND_SELF_MAX_CENTS, "5000", "消费者自助单笔退款上限（分），0=不限制");
        upsertIfAbsent(REFUND_SELF_MAX_DAILY, "3", "消费者每日自助退款次数上限，0=不限制");
        upsertIfAbsent(REFUND_SELF_PARTIAL_ENABLED, "true", "是否允许消费者按行自助部分退");
        upsertIfAbsent("debt.block_open_on_pending", "true", "有待支付订单时是否禁止开门");
        upsertIfAbsent(UNPAID_AUTO_CANCEL_HOURS, "48", "待支付订单超时自动关单小时数, 0=关闭");
        upsertIfAbsent(UNPAID_AUTO_BLACKLIST, FALSE, "待支付超时关单时是否自动拉黑用户");
        upsertIfAbsent(RECHARGE_AUTO_CANCEL_MINUTES, "30", "待支付充值单超时自动取消分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_OFFLINE_AUTO_LOCK_MINUTES, "10", "设备离线超时自动锁机分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, "45",
                "人工解锁后离线自动锁机宽限分钟数, 0=无宽限");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, FALSE,
                "设备恢复稳定在线后是否自动解锁起售（默认关闭）");
        upsertIfAbsent(MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES, "30",
                "柜机离线/停售即时通知冷却分钟数（同柜同类型），0=不冷却");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, "15",
                "自动解锁前需保持稳定在线分钟数, 0=关闭");
        upsertIfAbsent(DISPUTE_SLA_HOURS, "48", "争议工单 SLA 处理时限（小时）");
        upsertIfAbsent(DISPUTE_SLA_REMINDER_HOURS, "12", "争议 SLA 到期前提醒提前量（小时）");
        upsertIfAbsent(DISPUTE_SLA_WEBHOOK, "", "争议 SLA 提醒/逾期推送 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_DINGTALK_WEBHOOK, "", "运营告警：钉钉机器人 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_WECOM_WEBHOOK, "", "运营告警：企业微信机器人 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_WEBHOOK, "", "运营告警：通用 JSON Webhook URL（留空不推送）");
        upsertIfAbsent("ops.log_retention.notify_months", "6", "通知日志保留月数，0=不清理");
        upsertIfAbsent("ops.log_retention.points_months", "12", "积分日志保留月数，0=不清理");
        upsertIfAbsent(OPS_SCAN_DOOR_OPEN_MINUTES, "10", "柜门开启超时告警分钟数");
        upsertIfAbsent(OPS_SCAN_UPLOAD_STUCK_MINUTES, "5", "视频上传卡点告警分钟数");
        upsertIfAbsent(OPS_SCAN_RECOGNITION_STUCK_MINUTES, "3", "识别卡点告警分钟数");
        upsertIfAbsent(OPS_SCAN_SETTLEMENT_STUCK_MINUTES, "3", "结算卡点告警分钟数");
        upsertIfAbsent(OPS_BRAND_TITLE, "AI开门柜", "运营后台品牌标题（登录页主标题）");
        upsertIfAbsent(OPS_BRAND_SUBTITLE, "运营管理系统", "运营后台副标题（登录页副文案）");
        upsertIfAbsent(OPS_BRAND_SIDEBAR_TITLE, "AI开门柜运营", "侧栏展开时的品牌文案");
        upsertIfAbsent(OPS_BRAND_LOGO_URL, "", "品牌标志图片地址（留空则用标题末字）");
        // 兼容旧默认值中的英文 OPS
        repository.findById(OPS_BRAND_SIDEBAR_TITLE).ifPresent(row -> {
            if ("AI开门柜 OPS".equals(row.getConfigValue())) {
                row.setConfigValue("AI开门柜运营");
                row.setDescription("侧栏展开时的品牌文案");
                row.setUpdatedAt(Instant.now());
                repository.save(row);
            }
        });
        repository.findById(OPS_BRAND_LOGO_URL).ifPresent(row -> {
            if (row.getDescription() != null && row.getDescription().contains("Logo URL")) {
                row.setDescription("品牌标志图片地址（留空则用标题末字）");
                row.setUpdatedAt(Instant.now());
                repository.save(row);
            }
        });
        upsertIfAbsent(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS),
                "消费者开门预授权冻结金额(分)");
        refreshDescriptionIfPresent(OPS_SUPPORT_EMAIL,
                "运营支持邮箱（下发 C 端公开配置 supportEmail）");
        refreshDescriptionIfPresent(SETTLEMENT_MIN_CONFIDENCE,
                "自动结算最低整体识别置信度（低于则触发人工审；单品另看 SKU 扣款阈值）");
        refreshDescriptionIfPresent(DISPUTE_AUTO_OPEN,
                "识别低置信是否自动开争议工单；false 时仍结算但记日志（空车/超时等安全路径不受影响）");
    }

    private void refreshDescriptionIfPresent(String key, String description) {
        repository.findById(key).ifPresent(row -> {
            if (description.equals(row.getDescription())) {
                return;
            }
            row.setDescription(description);
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        });
    }

    private void upsertIfAbsent(String key, String value, String description) {
        if (!repository.existsById(key)) {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            config.setUpdatedAt(Instant.now());
            repository.save(config);
        }
    }

    private static SystemConfigDto toDto(SystemConfig config) {
        return new SystemConfigDto(
                config.getConfigKey(),
                config.getConfigValue(),
                config.getDescription(),
                config.getUpdatedAt()
        );
    }
}
