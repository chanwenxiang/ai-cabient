package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantPaymentOnboarding;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantPaymentOnboardingMapper extends BaseTradeMapper<MerchantPaymentOnboarding> {

    default Optional<MerchantPaymentOnboarding> findByMerchantAndChannel(String merchantId, String channel) {
        return Optional.ofNullable(selectOne(Wrappers.<MerchantPaymentOnboarding>lambdaQuery()
                .eq(MerchantPaymentOnboarding::getMerchantId, merchantId)
                .eq(MerchantPaymentOnboarding::getChannel, channel)));
    }

    default List<MerchantPaymentOnboarding> search(String merchantId, String channel, String status) {
        var q = Wrappers.<MerchantPaymentOnboarding>lambdaQuery()
                .orderByDesc(MerchantPaymentOnboarding::getUpdatedAt);
        if (merchantId != null && !merchantId.isBlank()) {
            q.eq(MerchantPaymentOnboarding::getMerchantId, merchantId.trim());
        }
        if (channel != null && !channel.isBlank()) {
            q.eq(MerchantPaymentOnboarding::getChannel, channel.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            q.eq(MerchantPaymentOnboarding::getStatus, status.trim().toUpperCase());
        }
        return selectList(q.last("LIMIT 500"));
    }
}
