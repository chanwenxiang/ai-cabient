package com.aicabinet.common.dto;

/** 运营后台品牌外观（登录页 / 侧栏 / 浏览器标题，可公开读取）。 */
public record OpsBrandDto(
        String title,
        String subtitle,
        String sidebarTitle,
        String logoUrl
) {}
