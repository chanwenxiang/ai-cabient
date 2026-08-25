package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseInventory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WarehouseInventoryMapper extends BaseTradeMapper<WarehouseInventory> {

    default List<WarehouseInventory> findByWarehouseIdAndQuantityGreaterThanOrderByExpiryDateAsc(String warehouseId, int quantity) {
    return selectList(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).gt(WarehouseInventory::getQuantity, quantity).orderByAsc(WarehouseInventory::getExpiryDate));
    }

    default List<WarehouseInventory> findByWarehouseIdOrderByExpiryDateAsc(String warehouseId) {
    return selectList(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).orderByAsc(WarehouseInventory::getExpiryDate));
    }

    WarehouseInventory _findByWarehouseSkuBatchForUpdateRaw(@Param("warehouseId") String warehouseId,
                                                            @Param("skuId") String skuId,
                                                            @Param("batchNo") String batchNo);

    default Optional<WarehouseInventory> findByWarehouseIdAndSkuIdAndBatchNo(String warehouseId, String skuId, String batchNo) {
    return Optional.ofNullable(selectOne(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).eq(WarehouseInventory::getSkuId, skuId).eq(WarehouseInventory::getBatchNo, batchNo)));
    }

    default Optional<WarehouseInventory> findByWarehouseIdAndSkuIdAndBatchNoForUpdate(String warehouseId,
                                                                                        String skuId,
                                                                                        String batchNo) {
        return Optional.ofNullable(_findByWarehouseSkuBatchForUpdateRaw(warehouseId, skuId, batchNo));
    }

    default List<WarehouseInventory> findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(String warehouseId, String skuId) {
    return selectList(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).eq(WarehouseInventory::getSkuId, skuId).orderByAsc(WarehouseInventory::getExpiryDate));
    }

    /** 按商品汇总仓库库存（warehouseId 为空时汇总全部仓库）。 */
    @Select({
            "<script>",
            "SELECT sku_id AS c0, COALESCE(SUM(quantity), 0) AS c1",
            "FROM warehouse_inventory",
            "WHERE quantity &gt; 0",
            "<if test='warehouseId != null'>AND warehouse_id = #{warehouseId}</if>",
            "GROUP BY sku_id",
            "</script>"
    })
    List<LinkedHashMap<String, Object>> _sumQtyBySku(@Param("warehouseId") String warehouseId);

    default List<Object[]> sumQtyBySku(String warehouseId) {
        return ColumnMapRows.toObjectRows(_sumQtyBySku(warehouseId), 2);
    }

}
