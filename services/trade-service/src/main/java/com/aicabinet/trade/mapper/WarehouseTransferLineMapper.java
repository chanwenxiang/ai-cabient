package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseTransferLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WarehouseTransferLineMapper extends BaseTradeMapper<WarehouseTransferLine> {
    default List<WarehouseTransferLine> findByTransferId(Long transferId) {
        return selectList(Wrappers.<WarehouseTransferLine>lambdaQuery()
                .eq(WarehouseTransferLine::getTransferId, transferId)
                .orderByAsc(WarehouseTransferLine::getLineId));
    }

    default void deleteByTransferId(Long transferId) {
        delete(Wrappers.<WarehouseTransferLine>lambdaQuery()
                .eq(WarehouseTransferLine::getTransferId, transferId));
    }
}
