package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserMerchant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpsUserMerchantMapper extends BaseTradeMapper<OpsUserMerchant> {

    default List<OpsUserMerchant> findByIdUserId(Long userId) {
        return selectList(Wrappers.<OpsUserMerchant>lambdaQuery().eq(OpsUserMerchant::getUserId, userId));
    }

    default void deleteByIdUserId(Long userId) {
        delete(Wrappers.<OpsUserMerchant>lambdaQuery().eq(OpsUserMerchant::getUserId, userId));
    }

    default boolean existsByIdUserId(Long userId) {
        return selectCount(Wrappers.<OpsUserMerchant>lambdaQuery().eq(OpsUserMerchant::getUserId, userId)) > 0;
    }

    default List<OpsUserMerchant> findByMerchantIdIn(Collection<String> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<OpsUserMerchant>lambdaQuery().in(OpsUserMerchant::getMerchantId, merchantIds));
    }

    /** G2-11 ⑦：按商户反查授权账号（走 idx_ops_user_merchant_merchant）。 */
    default List<OpsUserMerchant> findByMerchantId(String merchantId) {
        return selectList(Wrappers.<OpsUserMerchant>lambdaQuery()
                .eq(OpsUserMerchant::getMerchantId, merchantId)
                .orderByAsc(OpsUserMerchant::getUserId));
    }

    /** G2-11 ⑦：单行回收——只摘掉这一条绑定，不动同账号的其它商户。 */
    default void deleteByIdUserIdAndMerchantId(Long userId, String merchantId) {
        delete(Wrappers.<OpsUserMerchant>lambdaQuery()
                .eq(OpsUserMerchant::getUserId, userId)
                .eq(OpsUserMerchant::getMerchantId, merchantId));
    }
}
