package com.aicabinet.common.dto;

public record MerchantRoleTemplateDto(
        String templateKey,
        String templateName,
        String description,
        String permissionHint,
        int sortOrder
) {}
