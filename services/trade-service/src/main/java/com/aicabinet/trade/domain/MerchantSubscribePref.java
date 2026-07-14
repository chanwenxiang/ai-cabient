package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant_subscribe_pref")
public class MerchantSubscribePref {

    @EmbeddedId
    private MerchantSubscribePrefId id;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public MerchantSubscribePref() {}

    public MerchantSubscribePref(Long userId, String alertType) {
        this.id = new MerchantSubscribePrefId(userId, alertType);
        this.enabled = true;
    }

    public MerchantSubscribePrefId getId() { return id; }
    public void setId(MerchantSubscribePrefId id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
