package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantWalletAccount;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantWalletAccountMapper extends BaseTradeMapper<MerchantWalletAccount> {

    MerchantWalletAccount findByIdForUpdateRaw(@Param("merchantId") String merchantId);

    default Optional<MerchantWalletAccount> findByIdForUpdate(String merchantId) {
        return Optional.ofNullable(findByIdForUpdateRaw(merchantId));
    }
}
