package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PromotionActivity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PromotionActivityMapper extends BaseTradeMapper<PromotionActivity> {

    PromotionActivity _findByIdForUpdateRaw(@Param("activityId") Long activityId);

    default java.util.Optional<PromotionActivity> findByIdForUpdate(Long activityId) {
        return java.util.Optional.ofNullable(_findByIdForUpdateRaw(activityId));
    }

    default List<PromotionActivity> findByStatus(String status) {
    return selectList(Wrappers.<PromotionActivity>lambdaQuery().eq(PromotionActivity::getStatus, status));
    }

    default List<PromotionActivity> findByStatusAndStartTimeBeforeAndEndTimeAfter(String status, Instant now1, Instant now2) {
    return selectList(Wrappers.<PromotionActivity>lambdaQuery().eq(PromotionActivity::getStatus, status).lt(PromotionActivity::getStartTime, now1).gt(PromotionActivity::getEndTime, now2));
    }

    default List<PromotionActivity> findByActivityType(String activityType) {
    return selectList(Wrappers.<PromotionActivity>lambdaQuery().eq(PromotionActivity::getActivityType, activityType));
    }

}
