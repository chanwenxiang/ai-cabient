package com.aicabinet.trade.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_user_merchant")
public class OpsUserMerchant {

    @EmbeddedId
    private OpsUserMerchantId id;

    public OpsUserMerchant() {}

    public OpsUserMerchant(Long userId, String merchantId) {
        this.id = new OpsUserMerchantId(userId, merchantId);
    }

    public OpsUserMerchantId getId() { return id; }
    public void setId(OpsUserMerchantId id) { this.id = id; }
}
