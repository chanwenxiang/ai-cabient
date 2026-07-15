package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserMerchant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

        List<OpsUserMerchant> findByMerchantIdIn(@Param("merchantIds") Collection<String> merchantIds);

}
