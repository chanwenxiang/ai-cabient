package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("franchise")
public class Franchise {
    @TableId(type = IdType.AUTO)
    private Long franchiseId;

    
    private String franchiseName;
    
    private String franchiseCode;
    
    private String status;
    
    private String contactName;
    
    private String contactPhone;
    
    private String address;
    
    private String province;
    
    private String city;
    
    private String district;
    
    private java.math.BigDecimal commissionRate;
    
    private java.math.BigDecimal depositAmount;
    
    private String contractNumber;
    
    private Instant contractStartDate;
    
    private Instant contractEndDate;
    
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getFranchiseId() { return franchiseId; }
    public void setFranchiseId(Long franchiseId) { this.franchiseId = franchiseId; }
    
    public String getFranchiseName() { return franchiseName; }
    public void setFranchiseName(String franchiseName) { this.franchiseName = franchiseName; }
    
    public String getFranchiseCode() { return franchiseCode; }
    public void setFranchiseCode(String franchiseCode) { this.franchiseCode = franchiseCode; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    
    public java.math.BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(java.math.BigDecimal commissionRate) { this.commissionRate = commissionRate; }
    
    public java.math.BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(java.math.BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    
    public String getContractNumber() { return contractNumber; }
    public void setContractNumber(String contractNumber) { this.contractNumber = contractNumber; }
    
    public Instant getContractStartDate() { return contractStartDate; }
    public void setContractStartDate(Instant contractStartDate) { this.contractStartDate = contractStartDate; }
    
    public Instant getContractEndDate() { return contractEndDate; }
    public void setContractEndDate(Instant contractEndDate) { this.contractEndDate = contractEndDate; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
