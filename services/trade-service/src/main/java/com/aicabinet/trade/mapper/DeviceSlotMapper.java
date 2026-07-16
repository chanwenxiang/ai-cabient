package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceSlotMapper extends BaseTradeMapper<DeviceSlot> {

    default List<DeviceSlot> findByIdDeviceIdOrderByRowNoAscColNoAsc(String deviceId) {
        return selectList(Wrappers.<DeviceSlot>lambdaQuery()
                .eq(DeviceSlot::getDeviceId, deviceId)
                .orderByAsc(DeviceSlot::getRowNo)
                .orderByAsc(DeviceSlot::getColNo));
    }

    default long countByIdDeviceIdAndEnabledTrue(String deviceId) {
        Long c = selectCount(Wrappers.<DeviceSlot>lambdaQuery()
                .eq(DeviceSlot::getDeviceId, deviceId)
                .eq(DeviceSlot::isEnabled, true));
        return c == null ? 0 : c;
    }

    default List<DeviceSlot> findByEnabledTrueAndLastPhysicalQtyIsNotNull() {
        return selectList(Wrappers.<DeviceSlot>lambdaQuery()
                .eq(DeviceSlot::isEnabled, true)
                .isNotNull(DeviceSlot::getLastPhysicalQty));
    }

    default List<DeviceSlot> findByIdDeviceId(String deviceId) {
        return selectList(Wrappers.<DeviceSlot>lambdaQuery().eq(DeviceSlot::getDeviceId, deviceId));
    }

    /** Composite PK: (device_id, slot_code) — MyBatis-Plus selectById needs a single @TableId. */
    @Override
    default Optional<DeviceSlot> findById(Serializable id) {
        DeviceSlotId cid = asCompositeId(id);
        if (cid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<DeviceSlot>lambdaQuery()
                .eq(DeviceSlot::getDeviceId, cid.getDeviceId())
                .eq(DeviceSlot::getSlotCode, cid.getSlotCode())));
    }

    @Override
    default boolean existsById(Serializable id) {
        return findById(id).isPresent();
    }

    @Override
    default DeviceSlot save(DeviceSlot entity) {
        if (entity.getDeviceId() == null || entity.getSlotCode() == null) {
            throw new IllegalArgumentException("deviceId and slotCode are required for DeviceSlot");
        }
        DeviceSlot existing = selectOne(Wrappers.<DeviceSlot>lambdaQuery()
                .eq(DeviceSlot::getDeviceId, entity.getDeviceId())
                .eq(DeviceSlot::getSlotCode, entity.getSlotCode()));
        if (existing == null) {
            insert(entity);
        } else {
            update(entity, Wrappers.<DeviceSlot>lambdaUpdate()
                    .eq(DeviceSlot::getDeviceId, entity.getDeviceId())
                    .eq(DeviceSlot::getSlotCode, entity.getSlotCode()));
        }
        return entity;
    }

    @Override
    default void delete(DeviceSlot entity) {
        if (entity == null || entity.getDeviceId() == null || entity.getSlotCode() == null) {
            return;
        }
        delete(Wrappers.<DeviceSlot>lambdaQuery()
                .eq(DeviceSlot::getDeviceId, entity.getDeviceId())
                .eq(DeviceSlot::getSlotCode, entity.getSlotCode()));
    }

    private static DeviceSlotId asCompositeId(Serializable id) {
        if (id instanceof DeviceSlotId cid) {
            return cid;
        }
        return null;
    }
}
