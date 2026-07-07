package com.aicabinet.common.dto;

import java.util.List;

/** 当前登录运营账号概要（顶栏展示） */
public record OpsMeDto(
        Long userId,
        String phoneNumber,
        String name,
        List<String> roleNames,
        int permissionCount
) {}
