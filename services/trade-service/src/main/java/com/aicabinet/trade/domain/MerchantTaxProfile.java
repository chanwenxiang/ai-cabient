package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("merchant_tax_profile")
@Getter
@Setter
public class MerchantTaxProfile {
    @TableId(type = IdType.INPUT)
    private String merchantId;
    private String companyName;
    private String taxNo;
    private String address;
    private String bankName;
    private String bankAccount;
    private String phone;
    private Instant updatedAt = Instant.now();
}
