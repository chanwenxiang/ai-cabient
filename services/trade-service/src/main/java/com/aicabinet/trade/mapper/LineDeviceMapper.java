package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineDevice;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface LineDeviceMapper extends BaseTradeMapper<LineDevice> {

    default List<LineDevice> findByManagerId(Long managerId) {
        return selectList(Wrappers.<LineDevice>lambdaQuery().eq(LineDevice::getManagerId, managerId));
    }

    default List<LineDevice> findActiveByManagerId(Long managerId) {
        return selectList(Wrappers.<LineDevice>lambdaQuery()
                .eq(LineDevice::getManagerId, managerId)
                .eq(LineDevice::getStatus, "ACTIVE"));
    }

    default Optional<LineDevice> findByDeviceIdAndStatus(String deviceId, String status) {
        return Optional.ofNullable(selectOne(Wrappers.<LineDevice>lambdaQuery()
                .eq(LineDevice::getDeviceId, deviceId)
                .eq(LineDevice::getStatus, status)));
    }

    default List<LineDevice> findByStatus(String status) {
        return selectList(Wrappers.<LineDevice>lambdaQuery().eq(LineDevice::getStatus, status));
    }
}
