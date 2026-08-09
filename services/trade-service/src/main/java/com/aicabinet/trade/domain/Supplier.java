package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("supplier")
public class Supplier {
    @TableId(type = IdType.INPUT)
    private String supplierId;

    private String supplierName;
    private String contactName;
    private String contactPhone;
    private String status = "ACTIVE";
    private int paymentTermsDays = 30;
    private Long creditLimitCents;
    private Instant createdAt;

public String getSupplierId() { return supplierId; }
    public void setSupplierId(String supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getPaymentTermsDays() { return paymentTermsDays; }
    public void setPaymentTermsDays(int paymentTermsDays) {
        this.paymentTermsDays = paymentTermsDays > 0 ? paymentTermsDays : 30;
    }
    public Long getCreditLimitCents() { return creditLimitCents; }
    public void setCreditLimitCents(Long creditLimitCents) { this.creditLimitCents = creditLimitCents; }
    public Instant getCreatedAt() { return createdAt; }
}
