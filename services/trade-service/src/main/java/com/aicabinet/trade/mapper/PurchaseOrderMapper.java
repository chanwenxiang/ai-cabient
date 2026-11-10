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

    /**
     * 按「仓库 + 参考号」点查（R2-B14 商户端采购申请的幂等回放）。
     *
     * <p>取最新一条：ref_no 在库里没有唯一约束（内部单号回填也复用它），
     * 正常路径下同一 (warehouseId, 客户端键) 只会存在一张单。
     */
    default Optional<PurchaseOrder> findLatestByWarehouseIdAndRefNo(String warehouseId, String refNo) {
        return Optional.ofNullable(selectOne(Wrappers.<PurchaseOrder>lambdaQuery()
                .eq(PurchaseOrder::getWarehouseId, warehouseId)
                .eq(PurchaseOrder::getRefNo, refNo)
                .orderByDesc(PurchaseOrder::getPurchaseOrderId)
                .last("LIMIT 1")));
    }

    default List<PurchaseOrder> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<PurchaseOrder>lambdaQuery().orderByDesc(PurchaseOrder::getCreatedAt));
    }

    /** page 为 0-based；returnableOnly=true 时仅返回可退货采购单。 */
    default Page<PurchaseOrder> searchPage(String keyword, String warehouseId, boolean returnableOnly,
                                           boolean excludeTestRef, int page, int size) {
        var query = Wrappers.<PurchaseOrder>lambdaQuery().orderByDesc(PurchaseOrder::getCreatedAt);
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.eq(PurchaseOrder::getWarehouseId, warehouseId.trim());
        }
        if (returnableOnly) {
            query.in(PurchaseOrder::getStatus, List.of("RECEIVED", "PARTIAL_RECEIVED"));
        }
        if (excludeTestRef) {
            query.and(w -> w.isNull(PurchaseOrder::getRefNo)
                    .or(sub -> sub.notLike(PurchaseOrder::getRefNo, "E2E%")
                            .notLike(PurchaseOrder::getRefNo, "PO-SMOKE%")));
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
