package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_user_merchant")
@Getter
@Setter
public class OpsUserMerchant {

    @TableField(exist = false)
    private OpsUserMerchantId id;

    private Long userId;

    private String merchantId;

    public OpsUserMerchant() {}

    public OpsUserMerchant(Long userId, String merchantId) {
        setId(new OpsUserMerchantId(userId, merchantId));
    }

    public OpsUserMerchantId getId() {
        if (id == null && userId != null && merchantId != null) {
            id = new OpsUserMerchantId(userId, merchantId);
        }
        return id;
    }
    public void setId(OpsUserMerchantId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
            this.merchantId = id.getMerchantId();
        }
    }

}
