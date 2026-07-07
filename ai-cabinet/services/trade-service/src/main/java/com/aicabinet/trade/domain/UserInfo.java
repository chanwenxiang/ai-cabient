package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_info")
public class UserInfo {

    @Id
    private Long userId;

    @Column(nullable = false, length = 32)
    private String phoneNumber;

    @Column(length = 64)
    private String name;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "wx_open_id", length = 64)
    private String wxOpenId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getWxOpenId() { return wxOpenId; }
    public void setWxOpenId(String wxOpenId) { this.wxOpenId = wxOpenId; }
    public Instant getCreatedAt() { return createdAt; }
}
