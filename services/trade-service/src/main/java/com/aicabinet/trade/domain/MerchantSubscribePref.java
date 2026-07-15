package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;

@TableName("merchant_subscribe_pref")
public class MerchantSubscribePref {

    @TableField(exist = false)
    private MerchantSubscribePrefId id;

    private Long userId;

    private String alertType;

    private boolean enabled = true;

    private Instant updatedAt;

public MerchantSubscribePref() {}

    public MerchantSubscribePref(Long userId, String alertType) {
        setId(new MerchantSubscribePrefId(userId, alertType));
        this.enabled = true;
    }

public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public MerchantSubscribePrefId getId() {
        if (id == null && userId != null && alertType != null) {
            id = new MerchantSubscribePrefId(userId, alertType);
        }
        return id;
    }
    public void setId(MerchantSubscribePrefId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
            this.alertType = id.getAlertType();
        }
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }
}
