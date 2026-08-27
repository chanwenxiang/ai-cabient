package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantPaymentOnboarding;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantPaymentOnboardingMapper extends BaseTradeMapper<MerchantPaymentOnboarding> {

    MerchantPaymentOnboarding findByIdForUpdateRaw(@Param("onboardingId") Long onboardingId);

    default Optional<MerchantPaymentOnboarding> findByIdForUpdate(Long onboardingId) {
        return Optional.ofNullable(findByIdForUpdateRaw(onboardingId));
    }

    MerchantPaymentOnboarding findByMerchantAndChannelForUpdateRaw(
            @Param("merchantId") String merchantId, @Param("channel") String channel);

    default Optional<MerchantPaymentOnboarding> findByMerchantAndChannelForUpdate(
            String merchantId, String channel) {
        return Optional.ofNullable(findByMerchantAndChannelForUpdateRaw(merchantId, channel));
    }

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

    /** page 为 0-based；merchantIds 为 null 表示不限商户范围。 */
    default Page<MerchantPaymentOnboarding> searchPage(String merchantId, String channel, String status,
                                                       Collection<String> merchantIds, int page, int size) {
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
        if (merchantIds != null) {
            if (merchantIds.isEmpty()) {
                q.eq(MerchantPaymentOnboarding::getMerchantId, "__NONE__");
            } else {
                q.in(MerchantPaymentOnboarding::getMerchantId, merchantIds);
            }
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }
}
