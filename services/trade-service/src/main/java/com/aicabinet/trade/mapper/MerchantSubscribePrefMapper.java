package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantSubscribePref;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MerchantSubscribePrefMapper extends BaseTradeMapper<MerchantSubscribePref> {

    default List<MerchantSubscribePref> findByIdUserId(Long userId) {
    return selectList(Wrappers.<MerchantSubscribePref>lambdaQuery().eq(MerchantSubscribePref::getUserId, userId));
    }

    default List<MerchantSubscribePref> findByIdUserIdAndEnabledTrue(Long userId) {
    return selectList(Wrappers.<MerchantSubscribePref>lambdaQuery().eq(MerchantSubscribePref::getUserId, userId).eq(MerchantSubscribePref::isEnabled, true));
    }

    default List<MerchantSubscribePref> findByAlertTypeAndEnabledTrue(String alertType) {
        if (alertType == null || alertType.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<MerchantSubscribePref>lambdaQuery()
                .eq(MerchantSubscribePref::getAlertType, alertType.trim())
                .eq(MerchantSubscribePref::isEnabled, true));
    }

}
