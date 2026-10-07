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

    /**
     * V320 近效期预警：查「有库存、且在 N 天内到期（或已过期）」的批次。
     *
     * <p>🔴 <b>为什么在 SQL 里筛而不是内存过滤</b>：仓库批次会随SKU × 批次增长到
     * 数千行，一次性拉全量再过滤等于把整个仓库的库存塞进 JVM。
     * 走 SQL 还能用上既有索引 {@code idx_wh_inv_expiry (warehouse_id, expiry_date)}
     * （等值列在前，顺序正确）。
     *
     * <p><b>为什么含「已过期」</b>：已过期批次是最需要立刻处理的，
     * 若只查「未来 N 天内」，过期那批会永远查不出来。
     *
     * @param daysAhead 提前多少天算「近效期」
     */
    default Page<WarehouseInventory> findExpiringPage(String warehouseId,
                                                      int daysAhead,
                                                      int page,
                                                      int size) {
        var query = Wrappers.<WarehouseInventory>lambdaQuery()
                .gt(WarehouseInventory::getQuantity, 0)
                .isNotNull(WarehouseInventory::getExpiryDate)
                .le(WarehouseInventory::getExpiryDate,
                        java.time.LocalDate.now().plusDays(daysAhead))
                .orderByAsc(WarehouseInventory::getExpiryDate);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(WarehouseInventory::getWarehouseId, warehouseId.trim());
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
