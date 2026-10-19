package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;

import java.time.Instant;

/**
 * 用户同意留痕（AU-149，V394）。只存 user_id，不存 PII；
 * (user_id, consent_type, policy_version) 唯一 ⇒ 登录路径反复调用天然幂等。
 */
@TableName("user_consent_log")
public class UserConsentLog {

    public static final String TYPE_PRIVACY_POLICY = "PRIVACY_POLICY";
    public static final String TYPE_ACCOUNT_DELETE = "ACCOUNT_DELETE";

    @TableId(type = IdType.AUTO)
    private Long logId;

    private Long userId;

    private String consentType;

    private String policyVersion;

    @TableField(fill = FieldFill.INSERT)
    private Instant consentedAt;

    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getConsentType() {
        return consentType;
    }

    public void setConsentType(String consentType) {
        this.consentType = consentType;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Instant getConsentedAt() {
        return consentedAt;
    }

    public void setConsentedAt(Instant consentedAt) {
        this.consentedAt = consentedAt;
    }
}
