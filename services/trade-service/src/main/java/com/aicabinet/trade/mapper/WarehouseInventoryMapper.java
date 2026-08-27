package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseInventory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    /** page 为 0-based；仅返回 quantity &gt; 0 的批次。 */
    default Page<WarehouseInventory> searchPage(String warehouseId, String keyword, int page, int size) {
        var query = Wrappers.<WarehouseInventory>lambdaQuery()
                .gt(WarehouseInventory::getQuantity, 0)
                .orderByAsc(WarehouseInventory::getExpiryDate);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(WarehouseInventory::getWarehouseId, warehouseId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(WarehouseInventory::getSkuId, kw)
                    .or().like(WarehouseInventory::getBatchNo, kw));
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

    WarehouseInventory findByWarehouseSkuBatchForUpdateRaw(@Param("warehouseId") String warehouseId,
                                                            @Param("skuId") String skuId,
                                                            @Param("batchNo") String batchNo);

    default Optional<WarehouseInventory> findByWarehouseIdAndSkuIdAndBatchNo(String warehouseId, String skuId, String batchNo) {
    return Optional.ofNullable(selectOne(Wrappers.<WarehouseInventory>lambdaQuery().eq(WarehouseInventory::getWarehouseId, warehouseId).eq(WarehouseInventory::getSkuId, skuId).eq(WarehouseInventory::getBatchNo, batchNo)));
    }

    default Optional<WarehouseInventory> findByWarehouseIdAndSkuIdAndBatchNoForUpdate(String warehouseId,
                                                                                        String skuId,
                                                                                        String batchNo) {
        return Optional.ofNullable(findByWarehouseSkuBatchForUpdateRaw(warehouseId, skuId, batchNo));
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
    List<LinkedHashMap<String, Object>> selectSumQtyBySku(@Param("warehouseId") String warehouseId);

    default List<Object[]> sumQtyBySku(String warehouseId) {
        return ColumnMapRows.toObjectRows(selectSumQtyBySku(warehouseId), 2);
    }

}
