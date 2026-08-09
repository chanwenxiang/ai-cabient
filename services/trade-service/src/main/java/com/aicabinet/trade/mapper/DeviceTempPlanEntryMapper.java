package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceTempPlanEntry;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DeviceTempPlanEntryMapper extends BaseTradeMapper<DeviceTempPlanEntry> {

    default List<DeviceTempPlanEntry> findByPlanId(Long planId) {
        return selectList(Wrappers.<DeviceTempPlanEntry>lambdaQuery()
                .eq(DeviceTempPlanEntry::getPlanId, planId)
                .orderByAsc(DeviceTempPlanEntry::getStartMinute));
    }

    default void deleteByPlanId(Long planId) {
        delete(Wrappers.<DeviceTempPlanEntry>lambdaQuery()
                .eq(DeviceTempPlanEntry::getPlanId, planId));
    }
}
