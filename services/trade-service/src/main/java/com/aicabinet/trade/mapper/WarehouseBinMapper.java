package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseBin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WarehouseBinMapper extends BaseTradeMapper<WarehouseBin> {

    default List<WarehouseBin> findByWarehouseIdOrderByBinCodeAsc(String warehouseId) {
        return selectList(Wrappers.<WarehouseBin>lambdaQuery()
                .eq(WarehouseBin::getWarehouseId, warehouseId)
                .orderByAsc(WarehouseBin::getBinCode));
    }

    default Optional<WarehouseBin> findByWarehouseIdAndBinCode(String warehouseId, String binCode) {
        return Optional.ofNullable(selectOne(Wrappers.<WarehouseBin>lambdaQuery()
                .eq(WarehouseBin::getWarehouseId, warehouseId)
                .eq(WarehouseBin::getBinCode, binCode)));
    }
}
