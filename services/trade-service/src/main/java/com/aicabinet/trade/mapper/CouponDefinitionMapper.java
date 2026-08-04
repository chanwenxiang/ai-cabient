package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CouponDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponDefinitionMapper extends BaseTradeMapper<CouponDefinition> {

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
