package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceAvailabilityKpiDaily;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface DeviceAvailabilityKpiDailyMapper extends BaseTradeMapper<DeviceAvailabilityKpiDaily> {

    default Optional<DeviceAvailabilityKpiDaily> findFirstByOrderByKpiDateDesc() {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceAvailabilityKpiDaily>lambdaQuery()
                .orderByDesc(DeviceAvailabilityKpiDaily::getKpiDate)
                .last("LIMIT 1")));
    }
}
