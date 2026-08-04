package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertWarehouseRequest(
        @NotBlank(message = "仓库名称不能为空")
        @Size(max = 64)
        String warehouseName,
        @Size(max = 255)
        String address,
        String status
) {}
