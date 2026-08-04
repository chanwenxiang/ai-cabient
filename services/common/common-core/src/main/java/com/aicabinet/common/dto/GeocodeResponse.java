package com.aicabinet.common.dto;

/**
 * 地理编码结果（高德）。
 */
public record GeocodeResponse(Double longitude, Double latitude, String formattedAddress) {}
