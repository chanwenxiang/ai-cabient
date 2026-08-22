package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CouponDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CouponDefinitionMapper extends BaseTradeMapper<CouponDefinition> {

    CouponDefinition _findByIdForUpdateRaw(@Param("couponDefId") Long couponDefId);

    default Optional<CouponDefinition> findByIdForUpdate(Long couponDefId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(couponDefId));
    }

    default List<CouponDefinition> findByStatus(String status) {
    return selectList(Wrappers.<CouponDefinition>lambdaQuery().eq(CouponDefinition::getStatus, status));
    }

    default List<CouponDefinition> findByActivityId(Long activityId) {
    return selectList(Wrappers.<CouponDefinition>lambdaQuery().eq(CouponDefinition::getActivityId, activityId));
    }

    default long countByStatus(String status) {
    Long c = selectCount(Wrappers.<CouponDefinition>lambdaQuery().eq(CouponDefinition::getStatus, status));
    return c == null ? 0 : c;
    }

}
