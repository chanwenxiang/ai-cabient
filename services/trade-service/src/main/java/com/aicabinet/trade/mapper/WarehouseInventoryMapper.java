package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseInventory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WarehouseInventoryMapper extends BaseTradeMapper<WarehouseInventory> {

    default List<WarehouseInventory> findByWarehouseIdAndQuantityGreaterThanOrderByExpiryDateAsc(String warehouseId, int quantity) {
    return selectList(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).gt(WarehouseInventory::getQuantity, quantity).orderByAsc(WarehouseInventory::getExpiryDate));
    }

    default List<WarehouseInventory> findByWarehouseIdOrderByExpiryDateAsc(String warehouseId) {
    return selectList(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).orderByAsc(WarehouseInventory::getExpiryDate));
    }

    default Optional<WarehouseInventory> findByWarehouseIdAndSkuIdAndBatchNo(String warehouseId, String skuId, String batchNo) {
    return Optional.ofNullable(selectOne(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).eq(WarehouseInventory::getSkuId, skuId).eq(WarehouseInventory::getBatchNo, batchNo)));
    }

    default List<WarehouseInventory> findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(String warehouseId, String skuId) {
    return selectList(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).eq(WarehouseInventory::getSkuId, skuId).orderByAsc(WarehouseInventory::getExpiryDate));
    }

}
