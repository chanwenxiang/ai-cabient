package com.aicabinet.trade.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sku_catalog")
public class SkuCatalog {

    @Id
    @Column(length = 64)
    private String skuId;

    @Column(nullable = false, length = 128)
    private String skuName;

    @Column(nullable = false)
    private int priceCents;

    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public int getPriceCents() { return priceCents; }
    public void setPriceCents(int priceCents) { this.priceCents = priceCents; }
}
