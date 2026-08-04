package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.DeviceSkuPriceId;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceSkuPriceMapper extends BaseTradeMapper<DeviceSkuPrice> {

    default List<DeviceSkuPrice> findByIdDeviceIdIn(Collection<String> deviceIds) {
        return selectList(Wrappers.<DeviceSkuPrice>lambdaQuery().in(DeviceSkuPrice::getDeviceId, deviceIds));
    }

    default List<DeviceSkuPrice> findByIdDeviceId(String deviceId) {
        return selectList(Wrappers.<DeviceSkuPrice>lambdaQuery().eq(DeviceSkuPrice::getDeviceId, deviceId));
    }

    default Optional<DeviceSkuPrice> findByDeviceIdAndSkuId(String deviceId, String skuId) {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceSkuPrice>lambdaQuery()
                .eq(DeviceSkuPrice::getDeviceId, deviceId)
                .eq(DeviceSkuPrice::getSkuId, skuId)
                .last("LIMIT 1")));
    }

    /** Composite PK: (device_id, sku_id) — MyBatis-Plus selectById needs a single @TableId. */
    @Override
    default Optional<DeviceSkuPrice> findById(Serializable id) {
        DeviceSkuPriceId cid = asCompositeId(id);
        if (cid == null) {
            return Optional.empty();
        }
        return findByDeviceIdAndSkuId(cid.getDeviceId(), cid.getSkuId());
    }

    @Override
    default boolean existsById(Serializable id) {
        return findById(id).isPresent();
    }

    @Override
    default DeviceSkuPrice save(DeviceSkuPrice entity) {
        if (entity.getDeviceId() == null || entity.getSkuId() == null) {
            throw new IllegalArgumentException("deviceId and skuId are required for DeviceSkuPrice");
        }
        DeviceSkuPrice existing = selectOne(Wrappers.<DeviceSkuPrice>lambdaQuery()
                .eq(DeviceSkuPrice::getDeviceId, entity.getDeviceId())
                .eq(DeviceSkuPrice::getSkuId, entity.getSkuId())
                .last("LIMIT 1"));
        if (existing == null) {
            insert(entity);
        } else {
            update(entity, Wrappers.<DeviceSkuPrice>lambdaUpdate()
                    .eq(DeviceSkuPrice::getDeviceId, entity.getDeviceId())
                    .eq(DeviceSkuPrice::getSkuId, entity.getSkuId()));
        }
        return entity;
    }

    @Override
    default void delete(DeviceSkuPrice entity) {
        if (entity == null || entity.getDeviceId() == null || entity.getSkuId() == null) {
            return;
        }
        delete(Wrappers.<DeviceSkuPrice>lambdaQuery()
                .eq(DeviceSkuPrice::getDeviceId, entity.getDeviceId())
                .eq(DeviceSkuPrice::getSkuId, entity.getSkuId()));
    }

    private static DeviceSkuPriceId asCompositeId(Serializable id) {
        if (id instanceof DeviceSkuPriceId cid) {
            return cid;
        }
        return null;
    }
}
