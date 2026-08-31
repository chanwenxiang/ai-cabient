package com.aicabinet.common.dto;

public record MerchantDeviceReportDto(
        String deviceId,
        String deviceName,
        String onlineStatus,
        long orderTotal,
        long revenueTotalCents,
        long orderToday,
        long revenueTodayCents,
        long sessionTotal,
        long sessionActive,
        long avgOrderValueTodayCents,
        long avgOrderValueTotalCents,
        String routeCode,
        String address,
        boolean salesLocked,
        String salesLockReason,
        Integer currentTempC,
        String firmwareVersion
) {
    public MerchantDeviceReportDto(
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
                orderToday > 0 ? revenueTodayCents / orderToday : 0,
                orderTotal > 0 ? revenueTotalCents / orderTotal : 0,
                null,
                null,
                false,
                null,
                null,
                null
        );
    }

    public MerchantDeviceReportDto(
            String deviceId,
            String deviceName,
            String onlineStatus,
            long orderTotal,
            long revenueTotalCents,
            long orderToday,
            long revenueTodayCents,
            long sessionTotal,
            long sessionActive,
            long avgOrderValueTodayCents,
            long avgOrderValueTotalCents,
            String routeCode,
            String address
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
                avgOrderValueTodayCents,
                avgOrderValueTotalCents,
                routeCode,
                address,
                false,
                null,
                null,
                null
        );
    }
}
