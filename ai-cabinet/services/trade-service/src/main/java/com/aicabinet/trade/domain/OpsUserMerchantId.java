package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class OpsUserMerchantId implements Serializable {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "merchant_id", length = 32)
    private String merchantId;

    public OpsUserMerchantId() {}

    public OpsUserMerchantId(Long userId, String merchantId) {
        this.userId = userId;
        this.merchantId = merchantId;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpsUserMerchantId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(merchantId, that.merchantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, merchantId);
    }
}
