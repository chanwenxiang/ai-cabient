package com.aicabinet.common.dto;

/** 大屏/分析口径隔离：mock 或显式演示标记时前端展示横幅。 */
public record DataScopeDto(
        boolean demoData,
        boolean mockEnabled,
        String label
) {}
