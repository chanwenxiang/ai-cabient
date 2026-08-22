package com.aicabinet.common.dto;

/** 免密签约结果：mock 即时 active；生产支付宝返回签约表单待回调。 */
public record PayContractDto(
        String channel,
        boolean active,
        String contractId,
        String message,
        boolean pending,
        String signFormHtml
) {
    public PayContractDto(String channel, boolean active, String contractId, String message) {
        this(channel, active, contractId, message, false, null);
    }
}
