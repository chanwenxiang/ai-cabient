package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseTransferOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WarehouseTransferOrderMapper extends BaseTradeMapper<WarehouseTransferOrder> {
    default List<WarehouseTransferOrder> findRecent(String status) {
        var q = Wrappers.<WarehouseTransferOrder>lambdaQuery()
                .orderByDesc(WarehouseTransferOrder::getCreatedAt)
                .last("LIMIT 200");
        if (status != null && !status.isBlank()) {
            q.eq(WarehouseTransferOrder::getStatus, status.trim().toUpperCase());
        }
        return selectList(q);
    }
}
