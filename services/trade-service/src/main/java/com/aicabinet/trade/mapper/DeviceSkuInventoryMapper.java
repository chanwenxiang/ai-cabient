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
     * 开门柜「台账口径」低库存行，返回的 {@code quantity} 是**可售量**。
     *
     * <p>判据/数量源与 {@code SkuStockLevel.of(sellable, low_threshold).warning()}、
     * 备货建议 {@code WarehouseService.suggestFromLowStock} 同一条：可售 ≤ 阈值（含 0 缺货）。
     * 可售按「柜 + SKU」粒度判断是否走批次账本（见 XML {@code effectiveSellableQty}）——
     * 按柜整体切会把从未进过账本的 SKU 读成 0（2026-10-28 实测 EG943322621391）。
     */
    List<DeviceSkuInventory> findLowStockByDeviceIds(
            @org.apache.ibatis.annotations.Param("deviceIds") Collection<String> deviceIds);

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

    /**
     * 集群就绪 P-1：汇总台账<b>单语句原子扣减</b>（DB 层兜底，勿改回「读→算→写」）。
     *
     * <p>返回影响行数：1 = 扣减成功；0 = 现存不足或行不存在。{@code quantity >= take}
     * 谓词与扣减同一条 UPDATE，无检查-写入间隙；配合 V457 的
     * {@code chk_device_sku_inventory_qty_nonneg} 构成双保险。</p>
     *
     * <p>R18-5（四端审计 2026-11-12）：{@code version} 必须<b>在此处递增</b>——
     * {@code syncAggregateInventory} 的「读实体→汇总→save」走 {@code eq(version)} 实体级 CAS，
     * 本语句若不递增 version，CAS 对算术扣减失明，会把刚扣掉的量按旧快照写回（丢扣）。</p>
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE device_sku_inventory SET quantity = quantity - #{take}, version = version + 1, updated_at = NOW() "
                    + "WHERE device_id = #{deviceId} AND sku_id = #{skuId} AND quantity >= #{take}")
    int deductQuantityAtomic(@org.apache.ibatis.annotations.Param("deviceId") String deviceId,
                             @org.apache.ibatis.annotations.Param("skuId") String skuId,
                             @org.apache.ibatis.annotations.Param("take") int take);

    /** 同上：单语句原子累加（回库/增量），行不存在返回 0（调用方决定是否建档）。version 同步递增（R18-5）。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE device_sku_inventory SET quantity = quantity + #{add}, version = version + 1, updated_at = NOW() "
                    + "WHERE device_id = #{deviceId} AND sku_id = #{skuId}")
    int increaseQuantityAtomic(@org.apache.ibatis.annotations.Param("deviceId") String deviceId,
                               @org.apache.ibatis.annotations.Param("skuId") String skuId,
                               @org.apache.ibatis.annotations.Param("add") int add);

    /**
     * R18-4（四端审计 2026-11-12）：增量建档<b>单语句 upsert</b>。此前「行缺失→INSERT→
     * catch DuplicateKeyException→再 UPDATE」在 PG 下必然失败：唯一键冲突后事务进入
     * aborted（25P02），同事务第二条 UPDATE 抛「current transaction is aborted」，
     * 整笔回补 500 且增量丢失。INSERT…ON CONFLICT DO UPDATE 把建档与累加合成一个原子点，
     * 无「检查-写入」间隙，并发建档双方都成功且终量=两笔之和。返回恒 1（插入或更新都算）。
     * 默认 capacity=20 / low_threshold=2 与 {@code InventoryService#applyDelta} 原 Java 建档口径一致。
     */
    @org.apache.ibatis.annotations.Update(
            "INSERT INTO device_sku_inventory (device_id, sku_id, quantity, capacity, low_threshold, version, updated_at) "
                    + "VALUES (#{deviceId}, #{skuId}, #{add}, 20, 2, 0, NOW()) "
                    + "ON CONFLICT (device_id, sku_id) DO UPDATE SET "
                    + "quantity = device_sku_inventory.quantity + EXCLUDED.quantity, "
                    + "version = device_sku_inventory.version + 1, "
                    + "updated_at = NOW()")
    int upsertIncreaseQuantity(@org.apache.ibatis.annotations.Param("deviceId") String deviceId,
                               @org.apache.ibatis.annotations.Param("skuId") String skuId,
                               @org.apache.ibatis.annotations.Param("add") int add);
}
