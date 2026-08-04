package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

@TableName("merchant_replenishment_request_line")
public class MerchantReplenishmentRequestLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long requestId;

    private String skuId;

    private String skuName;

    private int suggestedQty;

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
