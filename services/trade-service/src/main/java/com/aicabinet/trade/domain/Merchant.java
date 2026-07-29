package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("merchant")
public class Merchant {

    @TableId(type = IdType.INPUT)
    private String merchantId;

    private String merchantName;

    private String contactPhone;

    private int platformRateBps = 1000;

    private String wechatReceiverId;

    private String status = "ACTIVE";

    private String remark;

    private String alertContactName;

    private String alertContactPhone;

    private boolean allowMerchantPlanogramEdit = false;

    private boolean allowMerchantPricingEdit = false;

    /** 功能包：现场作业 */
    private boolean packFieldEnabled = true;

    /** 功能包：经营工具 */
    private boolean packBizEnabled = true;

    /** 功能包：团队与设置 */
    private boolean packTeamEnabled = true;

    private String parentMerchantId;

    private Instant createdAt;

    private Instant updatedAt;

public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public int getPlatformRateBps() { return platformRateBps; }
    public void setPlatformRateBps(int platformRateBps) { this.platformRateBps = platformRateBps; }
    public String getWechatReceiverId() { return wechatReceiverId; }
    public void setWechatReceiverId(String wechatReceiverId) { this.wechatReceiverId = wechatReceiverId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getAlertContactName() { return alertContactName; }
    public void setAlertContactName(String alertContactName) { this.alertContactName = alertContactName; }
    public String getAlertContactPhone() { return alertContactPhone; }
    public void setAlertContactPhone(String alertContactPhone) { this.alertContactPhone = alertContactPhone; }
    public boolean isAllowMerchantPlanogramEdit() { return allowMerchantPlanogramEdit; }
    public void setAllowMerchantPlanogramEdit(boolean allowMerchantPlanogramEdit) { this.allowMerchantPlanogramEdit = allowMerchantPlanogramEdit; }
    public boolean isAllowMerchantPricingEdit() { return allowMerchantPricingEdit; }
    public void setAllowMerchantPricingEdit(boolean allowMerchantPricingEdit) { this.allowMerchantPricingEdit = allowMerchantPricingEdit; }
    public boolean isPackFieldEnabled() { return packFieldEnabled; }
    public void setPackFieldEnabled(boolean packFieldEnabled) { this.packFieldEnabled = packFieldEnabled; }
    public boolean isPackBizEnabled() { return packBizEnabled; }
    public void setPackBizEnabled(boolean packBizEnabled) { this.packBizEnabled = packBizEnabled; }
    public boolean isPackTeamEnabled() { return packTeamEnabled; }
    public void setPackTeamEnabled(boolean packTeamEnabled) { this.packTeamEnabled = packTeamEnabled; }
    public String getParentMerchantId() { return parentMerchantId; }
    public void setParentMerchantId(String parentMerchantId) { this.parentMerchantId = parentMerchantId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
