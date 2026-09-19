package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 单个功能开关的元数据 —— 运营台「功能开关」面板用。
 *
 * <p>来源是后端 classpath 资源 {@code ops/feature-flags.json}（功能开关注册表），
 * 由门禁 {@code scripts/check-feature-flags.mjs} 双向守卫它与源码的一致性。</p>
 *
 * @param key            配置键，与 {@code system_config.config_key} 一致
 * @param group          分组标签（结算与识别 / 运营告警 / 可观测性 …）
 * @param type           BOOLEAN / NUMBER / TEXT / TEXTAREA / SELECT
 * @param defaultValue   默认值（与 {@code SystemConfigService.ensureDefaults()} 的 seed 一致）
 * @param description    运营可读说明
 * @param unit           数值型单位（分钟/小时/分/℃/米/月），非数值型为 null
 * @param options        SELECT 型候选项，其余为 null
 * @param deprecated     是否已废弃（废弃项在运营台只读并展示原因）
 * @param deprecatedNote 废弃原因
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatureFlagDto(
        String key,
        String group,
        String type,
        @JsonProperty("default") String defaultValue,
        String description,
        String unit,
        List<FeatureFlagOptionDto> options,
        boolean deprecated,
        String deprecatedNote
) {}
