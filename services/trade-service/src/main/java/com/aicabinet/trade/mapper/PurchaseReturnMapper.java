package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseReturn;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PurchaseReturnMapper extends BaseTradeMapper<PurchaseReturn> {

    default List<PurchaseReturn> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.<PurchaseReturn>lambdaQuery().orderByDesc(PurchaseReturn::getCreatedAt));
    }

    /** page 为 0-based。 */
    default Page<PurchaseReturn> searchPage(String keyword, String warehouseId, int page, int size) {
        var query = Wrappers.<PurchaseReturn>lambdaQuery().orderByDesc(PurchaseReturn::getCreatedAt);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(PurchaseReturn::getWarehouseId, warehouseId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> {
                w.like(PurchaseReturn::getSupplierId, kw)
                        .or().like(PurchaseReturn::getWarehouseId, kw)
                        .or().like(PurchaseReturn::getNotes, kw);
                try {
                    long id = Long.parseLong(kw);
                    w.or().eq(PurchaseReturn::getReturnId, id)
                            .or().eq(PurchaseReturn::getPurchaseOrderId, id);
                } catch (NumberFormatException ignored) {
                    // not numeric
                }
            });
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }
}
