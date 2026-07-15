package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.EdgeDevice;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EdgeDeviceMapper extends BaseTradeMapper<EdgeDevice> {

    default Optional<EdgeDevice> findByDeviceId(String deviceId) {
    return Optional.ofNullable(selectOne(Wrappers.<EdgeDevice>lambdaQuery().eq(EdgeDevice::getDeviceId, deviceId)));
    }

    default List<EdgeDevice> findByStatus(String status) {
    return selectList(Wrappers.<EdgeDevice>lambdaQuery().eq(EdgeDevice::getStatus, status));
    }

}
