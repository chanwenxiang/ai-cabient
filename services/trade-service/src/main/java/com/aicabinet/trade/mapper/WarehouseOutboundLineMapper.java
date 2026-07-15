package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WarehouseOutboundLineMapper extends BaseTradeMapper<WarehouseOutboundLine> {

    default List<WarehouseOutboundLine> findByOutboundIdOrderByLineIdAsc(Long outboundId) {
    return selectList(Wrappers.<WarehouseOutboundLine>lambdaQuery().eq(WarehouseOutboundLine::getOutboundId, outboundId).orderByAsc(WarehouseOutboundLine::getLineId));
    }

    default List<WarehouseOutboundLine> findByOutboundIdAndDeviceIdOrderByLineIdAsc(Long outboundId, String deviceId) {
    return selectList(Wrappers.<WarehouseOutboundLine>lambdaQuery().eq(WarehouseOutboundLine::getOutboundId, outboundId).eq(WarehouseOutboundLine::getDeviceId, deviceId).orderByAsc(WarehouseOutboundLine::getLineId));
    }

        int sumAllocatedQty(@Param("warehouseId") String warehouseId, @Param("skuId") String skuId, @Param("batchNo") String batchNo);


}
