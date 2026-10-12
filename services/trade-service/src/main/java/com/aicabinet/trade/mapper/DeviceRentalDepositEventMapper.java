package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceRentalDepositEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DeviceRentalDepositEventMapper extends BaseTradeMapper<DeviceRentalDepositEvent> {

    default List<DeviceRentalDepositEvent> findByDepositOrderByCreatedDesc(Long depositId) {
        return selectList(Wrappers.<DeviceRentalDepositEvent>lambdaQuery()
                .eq(DeviceRentalDepositEvent::getDepositId, depositId)
                .orderByDesc(DeviceRentalDepositEvent::getEventId));
    }
}
