package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseTransferOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WarehouseTransferOrderMapper extends BaseTradeMapper<WarehouseTransferOrder> {

    WarehouseTransferOrder findByIdForUpdateRaw(@Param("transferId") Long transferId);

    default Optional<WarehouseTransferOrder> findByIdForUpdate(Long transferId) {
        return Optional.ofNullable(findByIdForUpdateRaw(transferId));
    }

    default List<WarehouseTransferOrder> findRecent(String status) {
        var q = Wrappers.<WarehouseTransferOrder>lambdaQuery()
                .orderByDesc(WarehouseTransferOrder::getCreatedAt)
                .last("LIMIT 200");
        if (status != null && !status.isBlank()) {
            q.eq(WarehouseTransferOrder::getStatus, status.trim().toUpperCase());
        }
        return selectList(q);
    }

    /** page 为 0-based。 */
    default Page<WarehouseTransferOrder> searchPage(String status, int page, int size) {
        var query = Wrappers.<WarehouseTransferOrder>lambdaQuery()
                .orderByDesc(WarehouseTransferOrder::getCreatedAt);
        if (status != null && !status.isBlank()) {
            query.eq(WarehouseTransferOrder::getStatus, status.trim().toUpperCase());
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }
}
