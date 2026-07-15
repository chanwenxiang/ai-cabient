package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceSlotMapper extends BaseTradeMapper<DeviceSlot> {

    default List<DeviceSlot> findByIdDeviceIdOrderByRowNoAscColNoAsc(String deviceId) {
    return selectList(Wrappers.<DeviceSlot>lambdaQuery().eq(DeviceSlot::getDeviceId, deviceId).orderByAsc(DeviceSlot::getRowNo).orderByAsc(DeviceSlot::getColNo));
    }

    default long countByIdDeviceIdAndEnabledTrue(String deviceId) {
    Long c = selectCount(Wrappers.<DeviceSlot>lambdaQuery().eq(DeviceSlot::getDeviceId, deviceId).eq(DeviceSlot::isEnabled, true));
    return c == null ? 0 : c;
    }

    default List<DeviceSlot> findByEnabledTrueAndLastPhysicalQtyIsNotNull() {
    return selectList(Wrappers.<DeviceSlot>lambdaQuery().eq(DeviceSlot::isEnabled, true).isNotNull(DeviceSlot::getLastPhysicalQty));
    }

    default List<DeviceSlot> findByIdDeviceId(String deviceId) {
    return selectList(Wrappers.<DeviceSlot>lambdaQuery().eq(DeviceSlot::getDeviceId, deviceId));
    }

}
