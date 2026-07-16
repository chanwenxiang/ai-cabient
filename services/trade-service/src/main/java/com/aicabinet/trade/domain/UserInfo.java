package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("user_info")
public class UserInfo {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String phoneNumber;

    private String name;

    private boolean verified;

    private String wxOpenId;

    private String passwordHash;

    /** ACTIVE / INACTIVE — 运营账号启停 */
    private String status = "ACTIVE";

    private boolean payscoreEnabled;

    private String payscoreContractId;

    private String alipayAgreementId;

    private String payPreferredChannel = "BALANCE";

    private Instant createdAt;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
