package com.aicabinet.common.dto;

import java.time.Instant;

/** 柜机流量费月结应付账单（台账，标记已付不触发自动扣款）。 */
public record DeviceDataFeeBillDto(
        Long billId,
        String deviceId,
        String deviceName,
        String merchantId,
        String billMonth,
        int amountCents,
        String status,
        Instant paidAt,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {}
