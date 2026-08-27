package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Supplier;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierMapper extends BaseTradeMapper<Supplier> {

    default List<Supplier> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<Supplier>lambdaQuery().orderByDesc(Supplier::getCreatedAt));
    }

    /** page 为 0-based。 */
    default Page<Supplier> searchPage(String keyword, int page, int size) {
        var query = Wrappers.<Supplier>lambdaQuery().orderByDesc(Supplier::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(Supplier::getSupplierId, kw)
                    .or().like(Supplier::getSupplierName, kw)
                    .or().like(Supplier::getContactName, kw)
                    .or().like(Supplier::getContactPhone, kw));
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
