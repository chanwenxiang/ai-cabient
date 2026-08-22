package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantTaxProfile;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantTaxProfileMapper extends BaseTradeMapper<MerchantTaxProfile> {

    MerchantTaxProfile _findByIdForUpdateRaw(@Param("merchantId") String merchantId);

    default Optional<MerchantTaxProfile> findByIdForUpdate(String merchantId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(merchantId));
    }
}
