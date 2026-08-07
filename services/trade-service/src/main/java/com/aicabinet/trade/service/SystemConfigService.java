package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
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
    /** 待支付订单超时自动关单小时数, 0=关闭自动关单. */
    public static final String UNPAID_AUTO_CANCEL_HOURS = "order.unpaid.auto_cancel_hours";
    /** 超时关单时是否自动拉黑用户. */
    public static final String UNPAID_AUTO_BLACKLIST = "order.unpaid.auto_blacklist";
    /** 待支付充值单超时自动取消分钟数, 0=关闭. */
    public static final String RECHARGE_AUTO_CANCEL_MINUTES = "recharge.pending.auto_cancel_minutes";
    /** 设备离线超过该分钟数后自动锁机停售, 0=不自动锁机. */
    public static final String DEVICE_OFFLINE_AUTO_LOCK_MINUTES = "device.offline.auto_sales_lock_minutes";
    public static final String DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED = "device.offline.auto_unlock_enabled";
    public static final String DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES = "device.offline.auto_unlock_stable_minutes";
    /** 消费者开门预授权冻结金额(分), 优先于配置文件, 柜机押金可覆盖. */
    public static final String CHECKOUT_PREAUTH_CENTS = "checkout.preauth_cents";

    private final SystemConfigMapper repository;
    private final SecurityProperties securityProperties;
    private final AlipayProperties alipayProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final PayScoreProperties payScoreProperties;

    public SystemConfigService(SystemConfigMapper repository,
                               SecurityProperties securityProperties,
                               AlipayProperties alipayProperties,
                               WeChatPayProperties weChatPayProperties,
                               PayScoreProperties payScoreProperties) {
        this.repository = repository;
        this.securityProperties = securityProperties;
        this.alipayProperties = alipayProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.payScoreProperties = payScoreProperties;
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
        map.put("preauthCents", getValue(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS)));
        return map;
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
        upsertIfAbsent(UNPAID_AUTO_CANCEL_HOURS, "48", "待支付订单超时自动关单小时数, 0=关闭");
        upsertIfAbsent(UNPAID_AUTO_BLACKLIST, "false", "待支付超时关单时是否自动拉黑用户");
        upsertIfAbsent(RECHARGE_AUTO_CANCEL_MINUTES, "30", "待支付充值单超时自动取消分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_OFFLINE_AUTO_LOCK_MINUTES, "10", "设备离线超时自动锁机分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, "false",
                "设备恢复稳定在线后是否自动解锁起售（默认关闭）");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, "15",
                "自动解锁前需保持稳定在线分钟数, 0=关闭");
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
