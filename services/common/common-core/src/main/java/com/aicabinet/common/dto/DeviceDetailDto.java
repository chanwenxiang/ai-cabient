package com.aicabinet.common.dto;

import java.util.List;

public record DeviceDetailDto(
        AdminDeviceDto device,
        DeviceOpsMetricsDto metrics,
        List<DeviceSlotDto> slots,
        List<DeviceInventoryDto> skuInventory,
        /** SKU 已下架但柜×商品台账行仍残留的死账（CB-080：不随下架自动清，admin 端可见可清；小程序端忽略本字段）。 */
        List<DeviceInventoryDto> delistedInventory
) {}
