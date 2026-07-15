package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.InventoryMovement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMovementMapper extends BaseTradeMapper<InventoryMovement> {

    default List<InventoryMovement> findByDeviceIdOrderByCreatedAtDesc(String deviceId) {
    return selectList(Wrappers.<InventoryMovement>lambdaQuery().eq(InventoryMovement::getDeviceId, deviceId).orderByDesc(InventoryMovement::getCreatedAt));
    }

}
