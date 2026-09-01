package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertSystemConfigRequest(
        @NotBlank(message = "配置键不能为空")
        @Size(max = 64, message = "配置键最长 64 个字符")
        String configKey,
        /** 允许空串（如品牌标志地址、Webhook 等可清空项）。 */
        @Size(max = 2048, message = "配置值最长 2048 个字符")
        String configValue,
        @Size(max = 256, message = "说明最长 256 个字符")
        String description
) {}
