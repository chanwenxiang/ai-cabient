package com.aicabinet.common.dto;

/** 商户补货：柜机是否在 FIELD 管辖范围内。 */
public record MerchantReplenishmentDeviceAccessDto(String deviceId, boolean allowed) {}
