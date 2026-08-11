package com.aicabinet.trade.domain;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OpsUserMerchantId implements Serializable {

    private Long userId;

    private String merchantId;

    public OpsUserMerchantId() {}

    public OpsUserMerchantId(Long userId, String merchantId) {
        this.userId = userId;
        this.merchantId = merchantId;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpsUserMerchantId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(merchantId, that.merchantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, merchantId);
    }
}
