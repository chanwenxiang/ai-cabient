package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceSkuPriceMapper extends BaseTradeMapper<DeviceSkuPrice> {

    default List<DeviceSkuPrice> findByIdDeviceIdIn(Collection<String> deviceIds) {
    return selectList(Wrappers.<DeviceSkuPrice>lambdaQuery().in(DeviceSkuPrice::getDeviceId, deviceIds));
    }

    default List<DeviceSkuPrice> findByIdDeviceId(String deviceId) {
    return selectList(Wrappers.<DeviceSkuPrice>lambdaQuery().eq(DeviceSkuPrice::getDeviceId, deviceId));
    }

}
