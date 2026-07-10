package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class MerchantSubscribePrefId implements Serializable {
    private Long userId;
    private String alertType;

    public MerchantSubscribePrefId() {}

    public MerchantSubscribePrefId(Long userId, String alertType) {
        this.userId = userId;
        this.alertType = alertType;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MerchantSubscribePrefId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(alertType, that.alertType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, alertType);
    }
}
