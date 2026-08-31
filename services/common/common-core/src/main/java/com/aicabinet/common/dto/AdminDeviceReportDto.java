package com.aicabinet.common.dto;

/**
 * 运营台设备经营报表行。
 * 在订单/营收/会话汇总外附带点位与机况字段，便于外勤与运维筛选。
 */
public record AdminDeviceReportDto(
        String deviceId,
        String deviceName,
        String onlineStatus,
        long orderTotal,
        long revenueTotalCents,
        long orderToday,
        long revenueTodayCents,
        long sessionTotal,
        long sessionActive,
        String merchantId,
        String merchantName,
        String routeCode,
        String address,
        boolean salesLocked,
        String salesLockReason,
        Integer currentTempC,
        String firmwareVersion,
        long avgOrderValueTodayCents,
        long avgOrderValueTotalCents
) {
    /** 兼容旧 9 字段构造。 */
    public AdminDeviceReportDto(
            String deviceId,
            String deviceName,
            String onlineStatus,
            long orderTotal,
            long revenueTotalCents,
            long orderToday,
            long revenueTodayCents,
            long sessionTotal,
            long sessionActive
    ) {
        this(
                deviceId,
                deviceName,
                onlineStatus,
                orderTotal,
                revenueTotalCents,
                orderToday,
                revenueTodayCents,
                sessionTotal,
                sessionActive,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                orderToday > 0 ? revenueTodayCents / orderToday : 0,
                orderTotal > 0 ? revenueTotalCents / orderTotal : 0
        );
    }
}
