package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseMovement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WarehouseMovementMapper extends BaseTradeMapper<WarehouseMovement> {

    default List<WarehouseMovement> findTop100ByWarehouseIdOrderByCreatedAtDesc(String warehouseId) {
    return selectList(Wrappers.<WarehouseMovement>lambdaQuery().eq(WarehouseMovement::getWarehouseId, warehouseId).orderByDesc(WarehouseMovement::getCreatedAt).last("LIMIT 100"));
    }

}
