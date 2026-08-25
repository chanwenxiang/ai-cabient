package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface MerchantMapper extends BaseTradeMapper<Merchant> {

    Merchant _findByIdForUpdateRaw(@Param("merchantId") String merchantId);

    default Optional<Merchant> findByIdForUpdate(String merchantId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(merchantId));
    }
}
