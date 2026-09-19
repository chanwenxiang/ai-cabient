package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 功能开关注册表（权威清单）。
 *
 * @param version 注册表版本
 * @param groups  分组顺序（运营台按此顺序渲染）
 * @param flags   全部开关
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatureFlagCatalogDto(
        int version,
        List<String> groups,
        List<FeatureFlagDto> flags
) {}
