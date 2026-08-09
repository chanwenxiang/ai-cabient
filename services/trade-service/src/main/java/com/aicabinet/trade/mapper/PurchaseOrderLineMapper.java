package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseOrderLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurchaseOrderLineMapper extends BaseTradeMapper<PurchaseOrderLine> {

    default List<PurchaseOrderLine> findByPurchaseOrderIdOrderByLineIdAsc(Long purchaseOrderId) {
    return selectList(Wrappers.<PurchaseOrderLine>lambdaQuery().eq(PurchaseOrderLine::getPurchaseOrderId, purchaseOrderId).orderByAsc(PurchaseOrderLine::getLineId));
    }

    /** 待收采购量（已下单未收货，按商品汇总；warehouseId 为空时汇总全部仓库）。 */
    @Select({
            "<script>",
            "SELECT l.sku_id AS c0, COALESCE(SUM(l.ordered_qty - l.received_qty), 0) AS c1",
            "FROM purchase_order_line l",
            "JOIN purchase_order o ON l.purchase_order_id = o.purchase_order_id",
            "WHERE o.status IN ('CREATED', 'PARTIAL_RECEIVED')",
            "<if test='warehouseId != null'>AND o.warehouse_id = #{warehouseId}</if>",
            "GROUP BY l.sku_id",
            "</script>"
    })
    List<LinkedHashMap<String, Object>> _pendingQtyBySku(@Param("warehouseId") String warehouseId);

    default List<Object[]> pendingQtyBySku(String warehouseId) {
        return ColumnMapRows.toObjectRows(_pendingQtyBySku(warehouseId), 2);
    }

}
