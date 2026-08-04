package com.aicabinet.common.dto;

public record RouteWaypointDto(
        int sequence,
        String deviceId,
        double latitude,
        double longitude,
        String address,
        int distanceFromPrevM
) {}
