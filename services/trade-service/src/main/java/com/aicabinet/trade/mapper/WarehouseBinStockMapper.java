package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseBinStock;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WarehouseBinStockMapper extends BaseTradeMapper<WarehouseBinStock> {

    WarehouseBinStock findByBinIdAndSkuIdAndBatchNoForUpdateRaw(
            @Param("binId") Long binId,
            @Param("skuId") String skuId,
            @Param("batchNo") String batchNo);

    default Optional<WarehouseBinStock> findByBinIdAndSkuIdAndBatchNoForUpdate(
            Long binId, String skuId, String batchNo) {
        return Optional.ofNullable(findByBinIdAndSkuIdAndBatchNoForUpdateRaw(binId, skuId, batchNo));
    }

    default List<WarehouseBinStock> findByBinIdOrderByExpiryDateAsc(Long binId) {
        return selectList(Wrappers.<WarehouseBinStock>lambdaQuery()
                .eq(WarehouseBinStock::getBinId, binId)
                .gt(WarehouseBinStock::getQuantity, 0)
                .orderByAsc(WarehouseBinStock::getExpiryDate)
                .orderByAsc(WarehouseBinStock::getSkuId));
    }

    default Optional<WarehouseBinStock> findByBinIdAndSkuIdAndBatchNo(Long binId, String skuId, String batchNo) {
        return Optional.ofNullable(selectOne(Wrappers.<WarehouseBinStock>lambdaQuery()
                .eq(WarehouseBinStock::getBinId, binId)
                .eq(WarehouseBinStock::getSkuId, skuId)
                .eq(WarehouseBinStock::getBatchNo, batchNo)));
    }
}
