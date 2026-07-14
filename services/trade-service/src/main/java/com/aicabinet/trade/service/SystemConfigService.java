package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.repository.SystemConfigRepository;
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

    private final SystemConfigRepository repository;
    private final SecurityProperties securityProperties;
    private final AlipayProperties alipayProperties;

    public SystemConfigService(SystemConfigRepository repository,
                               SecurityProperties securityProperties,
                               AlipayProperties alipayProperties) {
        this.repository = repository;
        this.securityProperties = securityProperties;
        this.alipayProperties = alipayProperties;
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
        map.put("alipayRechargeEnabled", String.valueOf(alipayProperties.isConfigured()));
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
