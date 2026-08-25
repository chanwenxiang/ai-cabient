package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceTempPlan;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface DeviceTempPlanMapper extends BaseTradeMapper<DeviceTempPlan> {

    default Optional<DeviceTempPlan> findByDeviceId(String deviceId) {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceTempPlan>lambdaQuery()
                .eq(DeviceTempPlan::getDeviceId, deviceId)));
    }

    default List<DeviceTempPlan> findAllEnabled() {
        return selectList(Wrappers.<DeviceTempPlan>lambdaQuery()
                .eq(DeviceTempPlan::isEnabled, true));
    }
}
