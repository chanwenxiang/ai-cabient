package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantOnboarding;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface MerchantOnboardingMapper extends BaseTradeMapper<MerchantOnboarding> {
    default List<MerchantOnboarding> findAllOrdered() {
        return selectList(Wrappers.<MerchantOnboarding>lambdaQuery().orderByDesc(MerchantOnboarding::getUpdatedAt));
    }

    default List<MerchantOnboarding> findByMerchantId(String merchantId) {
        return selectList(Wrappers.<MerchantOnboarding>lambdaQuery()
                .eq(MerchantOnboarding::getMerchantId, merchantId)
                .orderByDesc(MerchantOnboarding::getUpdatedAt));
    }
}
