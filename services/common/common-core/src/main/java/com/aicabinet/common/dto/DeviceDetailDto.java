package com.aicabinet.common.dto;

import java.util.List;

public record DeviceDetailDto(
        AdminDeviceDto device,
        DeviceOpsMetricsDto metrics,
        List<DeviceSlotDto> slots,
        List<DeviceInventoryDto> skuInventory
) {}
