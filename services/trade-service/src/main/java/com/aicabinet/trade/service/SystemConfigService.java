package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.mapper.SystemConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final SystemConfigMapper repository;
    private final SecurityProperties securityProperties;
    private final AlipayProperties alipayProperties;
    private final WeChatPayProperties weChatPayProperties;

    public SystemConfigService(SystemConfigMapper repository,
                               SecurityProperties securityProperties,
                               AlipayProperties alipayProperties,
                               WeChatPayProperties weChatPayProperties) {
        this.repository = repository;
        this.securityProperties = securityProperties;
        this.alipayProperties = alipayProperties;
        this.weChatPayProperties = weChatPayProperties;
    }

    @Transactional(readOnly = true)
    public String getValue(String key, String defaultValue) {
        return repository.findById(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public Map<String, String> consumerPublicConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("servicePhone", getValue(CONSUMER_SERVICE_PHONE, "400-888-0018"));
        map.put("mockEnabled", String.valueOf(securityProperties.mockEnabled()));
        // 沙箱：已配置支付宝密钥，或 mock 模式下允许走 mock 支付宝预下单（便于本地联调入口可见）
        boolean alipayOk = alipayProperties.isConfigured()
                || (securityProperties.mockEnabled() && alipayProperties.enabled());
        map.put("alipayRechargeEnabled", String.valueOf(alipayOk));
        // 微信：已完整配置走 live；未配置但开启 mock 时允许微信模拟预下单+确认
        boolean wechatOk = weChatPayProperties.isConfigured() || securityProperties.mockEnabled();
        map.put("wechatRechargeEnabled", String.valueOf(wechatOk));
        map.put("wechatPayLive", String.valueOf(weChatPayProperties.isConfigured()));
        // 支付分开门：mock 或显式开启时前端展示一键开通
        map.put("payScoreSignEnabled", String.valueOf(securityProperties.mockEnabled()));
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

    private void ensureDefaults() {
        upsertIfAbsent(CONSUMER_SERVICE_PHONE, "400-888-0018", "C 端客服电话");
        upsertIfAbsent(OPS_SUPPORT_EMAIL, "ops@aicabinet.local", "运营支持邮箱");
        upsertIfAbsent(SETTLEMENT_MIN_CONFIDENCE, "0.72", "自动结算最低识别置信度");
        upsertIfAbsent(DISPUTE_AUTO_OPEN, "true", "识别低置信是否自动开争议工单");
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
