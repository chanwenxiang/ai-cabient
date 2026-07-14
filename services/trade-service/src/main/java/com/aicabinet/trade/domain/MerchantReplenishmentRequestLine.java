package com.aicabinet.trade.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "merchant_replenishment_request_line")
public class MerchantReplenishmentRequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lineId;

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(length = 128)
    private String skuName;

    @Column(nullable = false)
    private int suggestedQty;

    @Column(nullable = false)
    private int requestedQty;

    public Long getLineId() { return lineId; }
    public void setLineId(Long lineId) { this.lineId = lineId; }
    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public int getSuggestedQty() { return suggestedQty; }
    public void setSuggestedQty(int suggestedQty) { this.suggestedQty = suggestedQty; }
    public int getRequestedQty() { return requestedQty; }
    public void setRequestedQty(int requestedQty) { this.requestedQty = requestedQty; }
}
