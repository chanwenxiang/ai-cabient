package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceAvailabilityKpiDaily;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface DeviceAvailabilityKpiDailyMapper extends BaseTradeMapper<DeviceAvailabilityKpiDaily> {

    default Optional<DeviceAvailabilityKpiDaily> findFirstByOrderByKpiDateDesc() {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceAvailabilityKpiDaily>lambdaQuery()
                .orderByDesc(DeviceAvailabilityKpiDaily::getKpiDate)
                .last("LIMIT 1")));
    }

    default List<DeviceAvailabilityKpiDaily> findTopNByOrderByKpiDateDesc(int limit) {
        int lim = Math.max(1, Math.min(limit, 365));
        return selectList(Wrappers.<DeviceAvailabilityKpiDaily>lambdaQuery()
                .orderByDesc(DeviceAvailabilityKpiDaily::getKpiDate)
                .last("LIMIT " + lim));
    }
}
