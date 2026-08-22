package com.aicabinet.trade.service;

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
    /** 消费者开门预授权冻结金额(分), 优先于配置文件, 柜机押金可覆盖. */
    public static final String CHECKOUT_PREAUTH_CENTS = "checkout.preauth_cents";
    /** 纯视觉柜（会话无重力字段）空车是否自动零结；默认 false 进争议。 */
    public static final String SETTLEMENT_EMPTY_AUTO_NO_GRAVITY =
            "settlement.empty_auto_complete_no_gravity";

    private final SystemConfigMapper repository;
    private final SecurityProperties securityProperties;
    private final AlipayProperties alipayProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final PayScoreProperties payScoreProperties;
    private final WeChatWebProperties weChatWebProperties;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final QrProperties qrProperties;

    public SystemConfigService(SystemConfigMapper repository,
                               SecurityProperties securityProperties,
                               AlipayProperties alipayProperties,
                               WeChatPayProperties weChatPayProperties,
                               PayScoreProperties payScoreProperties,
                               WeChatWebProperties weChatWebProperties,
                               WeChatMiniAppProperties weChatMiniAppProperties,
                               QrProperties qrProperties) {
        this.repository = repository;
        this.securityProperties = securityProperties;
        this.alipayProperties = alipayProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.payScoreProperties = payScoreProperties;
        this.weChatWebProperties = weChatWebProperties;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.qrProperties = qrProperties;
    }

    @Transactional(readOnly = true)
    public String getValue(String key, String defaultValue) {
        return repository.findById(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        String raw = getValue(key, null);
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
        String raw = getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    @Transactional(readOnly = true)
    public Map<String, String> consumerPublicConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("servicePhone", getValue(CONSUMER_SERVICE_PHONE, "400-888-0018"));
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
        map.put("refundDefaultPolicy", getValue(REFUND_DEFAULT_POLICY, "AUTO_REFUND"));
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
        map.put("preauthCents", getValue(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS)));
        return map;
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
        return upsert(request.configKey(), request.configValue(), request.description());
    }

    @Transactional
    public SystemConfigDto upsert(String key, String value, String description) {
        SystemConfig config = repository.findById(key).orElseGet(SystemConfig::new);
        config.setConfigKey(key);
        config.setConfigValue(value);
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
        if (!repository.existsById(configKey)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "参数不存在");
        }
        repository.deleteById(configKey);
    }

    private void ensureDefaults() {
        upsertIfAbsent(CONSUMER_SERVICE_PHONE, "400-888-0018", "C端客服电话");
        upsertIfAbsent(OPS_SUPPORT_EMAIL, "ops@aicabinet.local", "运营支持邮箱");
        upsertIfAbsent(SETTLEMENT_MIN_CONFIDENCE, "0.72", "自动结算最低识别置信度");
        upsertIfAbsent(DISPUTE_AUTO_OPEN, "true", "识别低置信是否自动开争议工单");
        upsertIfAbsent(REFUND_DEFAULT_POLICY, "AUTO_REFUND",
                "全局默认退款策略: AUTO_REFUND=自助退款, DISPUTE_ONLY=仅申诉");
        upsertIfAbsent(REFUND_SELF_MAX_HOURS, "24", "消费者自助退款时限（下单后小时数）");
        upsertIfAbsent(REFUND_SELF_MAX_CENTS, "5000", "消费者自助单笔退款上限（分），0=不限制");
        upsertIfAbsent(REFUND_SELF_MAX_DAILY, "3", "消费者每日自助退款次数上限，0=不限制");
        upsertIfAbsent(REFUND_SELF_PARTIAL_ENABLED, "true", "是否允许消费者按行自助部分退");
        upsertIfAbsent("debt.block_open_on_pending", "true", "有待支付订单时是否禁止开门");
        upsertIfAbsent(UNPAID_AUTO_CANCEL_HOURS, "48", "待支付订单超时自动关单小时数, 0=关闭");
        upsertIfAbsent(UNPAID_AUTO_BLACKLIST, "false", "待支付超时关单时是否自动拉黑用户");
        upsertIfAbsent(RECHARGE_AUTO_CANCEL_MINUTES, "30", "待支付充值单超时自动取消分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_OFFLINE_AUTO_LOCK_MINUTES, "10", "设备离线超时自动锁机分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, "45",
                "人工解锁后离线自动锁机宽限分钟数, 0=无宽限");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, "false",
                "设备恢复稳定在线后是否自动解锁起售（默认关闭）");
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
        upsertIfAbsent(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS),
                "消费者开门预授权冻结金额(分)");
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
