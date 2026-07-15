package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdSlot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface AdSlotMapper extends BaseTradeMapper<AdSlot> {

    default Optional<AdSlot> findBySlotCode(String slotCode) {
    return Optional.ofNullable(selectOne(Wrappers.<AdSlot>lambdaQuery().eq(AdSlot::getSlotCode, slotCode)));
    }

    default List<AdSlot> findByStatus(String status) {
    return selectList(Wrappers.<AdSlot>lambdaQuery().eq(AdSlot::getStatus, status));
    }

    default List<AdSlot> findByDeviceId(String deviceId) {
    return selectList(Wrappers.<AdSlot>lambdaQuery().eq(AdSlot::getDeviceId, deviceId));
    }

}
