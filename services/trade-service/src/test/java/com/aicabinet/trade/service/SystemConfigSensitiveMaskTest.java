package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感配置脱敏口径（2026-11-02）：
 * 列表接口下发掩码、历史/审计按敏感键脱敏，避免授权码经「配置版本」回读。
 */
class SystemConfigSensitiveMaskTest {

    @Test
    void sensitiveKeysAreMasked() {
        assertThat(SystemConfigService.isSensitiveKey("ops.alert.email.password")).isTrue();
        assertThat(SystemConfigService.isSensitiveKey("ops.alert.feishu_webhook")).isTrue();
        assertThat(SystemConfigService.isSensitiveKey("ops.alert.webhook")).isTrue();
        assertThat(SystemConfigService.isSensitiveKey("ops.wechat.app_secret")).isTrue();
        assertThat(SystemConfigService.isSensitiveKey("oss.access_key")).isTrue();
        assertThat(SystemConfigService.maskValue("ops.alert.email.password", "VEOi6c...")).isEqualTo("******");
    }

    @Test
    void nonSensitiveAndBlankValuesAreNotMasked() {
        assertThat(SystemConfigService.isSensitiveKey("ops.alert.email.to")).isFalse();
        assertThat(SystemConfigService.isSensitiveKey("consumer.service_phone")).isFalse();
        assertThat(SystemConfigService.maskValue("ops.alert.email.to", "a@b.com")).isEqualTo("a@b.com");
        assertThat(SystemConfigService.maskValue("ops.alert.email.password", "")).isEmpty();
        assertThat(SystemConfigService.maskValue("ops.alert.email.password", null)).isNull();
    }
}
