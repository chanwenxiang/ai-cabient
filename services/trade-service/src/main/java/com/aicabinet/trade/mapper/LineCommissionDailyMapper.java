package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineCommissionDaily;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.Optional;

@Mapper
public interface LineCommissionDailyMapper extends BaseTradeMapper<LineCommissionDaily> {

    default Optional<LineCommissionDaily> findByManagerIdAndBizDateAndDeviceId(
            Long managerId, LocalDate bizDate, String deviceId) {
        return Optional.ofNullable(selectOne(Wrappers.<LineCommissionDaily>lambdaQuery()
                .eq(LineCommissionDaily::getManagerId, managerId)
                .eq(LineCommissionDaily::getBizDate, bizDate)
                .eq(LineCommissionDaily::getDeviceId, deviceId)));
    }

    /** 审计 T4（2026-10-15）：device-day 维度查重（不论 manager）——换绑线长防同日双付。 */
    default boolean existsByBizDateAndDeviceId(LocalDate bizDate, String deviceId) {
        return selectCount(Wrappers.<LineCommissionDaily>lambdaQuery()
                .eq(LineCommissionDaily::getBizDate, bizDate)
                .eq(LineCommissionDaily::getDeviceId, deviceId)) > 0;
    }
}
