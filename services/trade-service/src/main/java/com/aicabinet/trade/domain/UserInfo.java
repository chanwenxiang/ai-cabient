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

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "payscore_enabled", nullable = false)
    private boolean payscoreEnabled;

    @Column(name = "payscore_contract_id", length = 64)
    private String payscoreContractId;

    @Column(name = "alipay_agreement_id", length = 64)
    private String alipayAgreementId;

    @Column(name = "pay_preferred_channel", nullable = false, length = 16)
    private String payPreferredChannel = "BALANCE";

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
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isPayscoreEnabled() { return payscoreEnabled; }
    public void setPayscoreEnabled(boolean payscoreEnabled) { this.payscoreEnabled = payscoreEnabled; }
    public String getPayscoreContractId() { return payscoreContractId; }
    public void setPayscoreContractId(String payscoreContractId) { this.payscoreContractId = payscoreContractId; }
    public String getAlipayAgreementId() { return alipayAgreementId; }
    public void setAlipayAgreementId(String alipayAgreementId) { this.alipayAgreementId = alipayAgreementId; }
    public String getPayPreferredChannel() { return payPreferredChannel; }
    public void setPayPreferredChannel(String payPreferredChannel) { this.payPreferredChannel = payPreferredChannel; }
    public Instant getCreatedAt() { return createdAt; }
}
