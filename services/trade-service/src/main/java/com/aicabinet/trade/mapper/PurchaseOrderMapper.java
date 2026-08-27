package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PurchaseOrderMapper extends BaseTradeMapper<PurchaseOrder> {

    PurchaseOrder findByIdForUpdateRaw(@Param("purchaseOrderId") Long purchaseOrderId);

    default Optional<PurchaseOrder> findByIdForUpdate(Long purchaseOrderId) {
        return Optional.ofNullable(findByIdForUpdateRaw(purchaseOrderId));
    }

    default List<PurchaseOrder> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<PurchaseOrder>lambdaQuery().orderByDesc(PurchaseOrder::getCreatedAt));
    }

    /** page 为 0-based；returnableOnly=true 时仅返回可退货采购单。 */
    default Page<PurchaseOrder> searchPage(String keyword, String warehouseId, boolean returnableOnly, int page, int size) {
        var query = Wrappers.<PurchaseOrder>lambdaQuery().orderByDesc(PurchaseOrder::getCreatedAt);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(PurchaseOrder::getWarehouseId, warehouseId.trim());
        }
        if (returnableOnly) {
            query.in(PurchaseOrder::getStatus, List.of("RECEIVED", "PARTIAL_RECEIVED"));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> {
                w.like(PurchaseOrder::getRefNo, kw)
                        .or().like(PurchaseOrder::getSupplierId, kw);
                try {
                    long id = Long.parseLong(kw);
                    w.or().eq(PurchaseOrder::getPurchaseOrderId, id);
                } catch (NumberFormatException ignored) {
                    // not numeric id
                }
            });
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
