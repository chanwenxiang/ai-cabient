package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("merchant_onboarding")
public class MerchantOnboarding {
    @TableId(type = IdType.AUTO)
    private Long onboardingId;
    private String merchantId;
    private String subjectType;
    private String alipayRegStatus;
    private String wechatPayscoreStatus;
    private String onboardStatus;
    private String externalMerchantNo;
    private String remark;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getOnboardingId() { return onboardingId; }
    public void setOnboardingId(Long onboardingId) { this.onboardingId = onboardingId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getAlipayRegStatus() { return alipayRegStatus; }
    public void setAlipayRegStatus(String alipayRegStatus) { this.alipayRegStatus = alipayRegStatus; }
    public String getWechatPayscoreStatus() { return wechatPayscoreStatus; }
    public void setWechatPayscoreStatus(String wechatPayscoreStatus) { this.wechatPayscoreStatus = wechatPayscoreStatus; }
    public String getOnboardStatus() { return onboardStatus; }
    public void setOnboardStatus(String onboardStatus) { this.onboardStatus = onboardStatus; }
    public String getExternalMerchantNo() { return externalMerchantNo; }
    public void setExternalMerchantNo(String externalMerchantNo) { this.externalMerchantNo = externalMerchantNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
