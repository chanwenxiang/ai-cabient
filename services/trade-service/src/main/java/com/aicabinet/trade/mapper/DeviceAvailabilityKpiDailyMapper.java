package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceAvailabilityKpiDaily;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceAvailabilityKpiDailyMapper extends BaseTradeMapper<DeviceAvailabilityKpiDaily> {

    DeviceAvailabilityKpiDaily findByIdForUpdateRaw(@Param("kpiDate") LocalDate kpiDate);

    default Optional<DeviceAvailabilityKpiDaily> findByIdForUpdate(LocalDate kpiDate) {
        return Optional.ofNullable(findByIdForUpdateRaw(kpiDate));
    }

    default Optional<DeviceAvailabilityKpiDaily> findFirstByOrderByKpiDateDesc() {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceAvailabilityKpiDaily>lambdaQuery()
                .orderByDesc(DeviceAvailabilityKpiDaily::getKpiDate)
                .last("LIMIT 1")));
    }
}
