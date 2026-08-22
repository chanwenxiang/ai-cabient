package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantOpsConfig;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantOpsConfigMapper extends BaseTradeMapper<MerchantOpsConfig> {

    MerchantOpsConfig _findByIdForUpdateRaw(@Param("merchantId") String merchantId);

    default Optional<MerchantOpsConfig> findByIdForUpdate(String merchantId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(merchantId));
    }
}
