package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceSkuInventoryMapper extends BaseTradeMapper<DeviceSkuInventory> {

    default List<DeviceSkuInventory> findByIdDeviceId(String deviceId) {
        return selectList(Wrappers.<DeviceSkuInventory>lambdaQuery().eq(DeviceSkuInventory::getDeviceId, deviceId));
    }

    default List<DeviceSkuInventory> findByIdDeviceIdIn(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<DeviceSkuInventory>lambdaQuery().in(DeviceSkuInventory::getDeviceId, deviceIds));
    }

    /**
     * 开门柜「台账口径」低库存行：{@code quantity <= low_threshold}。
     *
     * <p>🔴 刻意**不走** {@code effectiveSellableQty}（批次账本口径）：开门柜（判据=将邑绑定）由
     * 端侧按 SKU 上报数量，没有货道/批次概念。批次口径用「该柜是否存在任意 lot 行」整体切换，
     * 只要残留一条 lot 就会把「从未进过账本的 SKU」读成 0 可售 ⇒ 整柜误判缺货
     * （2026-10-28 实测 EG943322621391：台账 2/1/1/4/4/4，批次口径算成 0/1/1/0/0/0）。
     * 本方法与备货建议 {@code WarehouseService.suggestFromLowStock} 同源，保证
     * 「首页说缺的，备货建议里一定有」。
     */
    default List<DeviceSkuInventory> findLowStockByDeviceIds(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<DeviceSkuInventory>lambdaQuery()
                .in(DeviceSkuInventory::getDeviceId, deviceIds)
                .apply("quantity <= low_threshold")
                .orderByAsc(DeviceSkuInventory::getDeviceId)
                .orderByAsc(DeviceSkuInventory::getSkuId));
    }

    /** 无设备过滤时的浏览上限，避免全表加载。 */
    default List<DeviceSkuInventory> findAllLimit(int limit) {
        int lim = Math.max(1, Math.min(limit, 5000));
        return selectList(Wrappers.<DeviceSkuInventory>lambdaQuery()
                .orderByAsc(DeviceSkuInventory::getDeviceId)
                .orderByAsc(DeviceSkuInventory::getSkuId)
                .last("LIMIT " + lim));
    }

        long countLowStock();

        List<DeviceSkuInventory> findLowStock();

        List<DeviceSkuInventory> findLowStockLimit(@org.apache.ibatis.annotations.Param("limit") int limit);

        long countLowStockByDeviceIds(@org.apache.ibatis.annotations.Param("deviceIds") Collection<String> deviceIds);

    /** Composite PK: (device_id, sku_id) — MyBatis-Plus selectById needs a single @TableId. */
    @Override
    default Optional<DeviceSkuInventory> findById(Serializable id) {
        DeviceSkuInventoryId cid = asCompositeId(id);
        if (cid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<DeviceSkuInventory>lambdaQuery()
                .eq(DeviceSkuInventory::getDeviceId, cid.getDeviceId())
                .eq(DeviceSkuInventory::getSkuId, cid.getSkuId())));
    }

    @Override
    default boolean existsById(Serializable id) {
        return findById(id).isPresent();
    }

    @Override
    default DeviceSkuInventory save(DeviceSkuInventory entity) {
        if (entity.getDeviceId() == null || entity.getSkuId() == null) {
            throw new IllegalArgumentException("deviceId and skuId are required for DeviceSkuInventory");
        }
        DeviceSkuInventory existing = selectOne(Wrappers.<DeviceSkuInventory>lambdaQuery()
                .eq(DeviceSkuInventory::getDeviceId, entity.getDeviceId())
                .eq(DeviceSkuInventory::getSkuId, entity.getSkuId()));
        if (existing == null) {
            if (entity.getVersion() < 0) {
                entity.setVersion(0);
            }
            insert(entity);
        } else {
            long expected = entity.getVersion();
            entity.setVersion(expected + 1);
            int rows = update(entity, Wrappers.<DeviceSkuInventory>lambdaUpdate()
                    .eq(DeviceSkuInventory::getDeviceId, entity.getDeviceId())
                    .eq(DeviceSkuInventory::getSkuId, entity.getSkuId())
                    .eq(DeviceSkuInventory::getVersion, expected));
            if (rows == 0) {
                entity.setVersion(expected);
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        com.aicabinet.trade.support.ApiMessages.OPTIMISTIC_LOCK_CONFLICT);
            }
        }
        return entity;
    }

    private static DeviceSkuInventoryId asCompositeId(Serializable id) {
        if (id instanceof DeviceSkuInventoryId cid) {
            return cid;
        }
        return null;
    }
}
