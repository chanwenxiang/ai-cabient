package com.aicabinet.trade.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "device_sku_price")
public class DeviceSkuPrice {

    @EmbeddedId
    private DeviceSkuPriceId id = new DeviceSkuPriceId();

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = Instant.now();
    }

    public DeviceSkuPriceId getId() { return id; }
    public void setId(DeviceSkuPriceId id) { this.id = id; }
    public int getPriceCents() { return priceCents; }
    public void setPriceCents(int priceCents) { this.priceCents = priceCents; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(Long updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
