package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseMonthlyCloseSheet;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

public interface WarehouseMonthlyCloseSheetMapper extends BaseTradeMapper<WarehouseMonthlyCloseSheet> {
    /** 仓+月互斥事务锁：缺单时的幻写也由同一把 advisory xact lock 挡住。 */
    @Select("SELECT pg_advisory_xact_lock(hashtext(#{lockKey}))")
    String lockWarehouseMonth(@Param("lockKey") String lockKey);

    default Optional<WarehouseMonthlyCloseSheet> findByIdForUpdate(Long closeId) {
        return Optional.ofNullable(selectOne(Wrappers.<WarehouseMonthlyCloseSheet>lambdaQuery()
                .eq(WarehouseMonthlyCloseSheet::getCloseId, closeId)
                .last("FOR UPDATE")));
    }

    default Optional<WarehouseMonthlyCloseSheet> findByWarehouseMonthForUpdate(String warehouseId,
                                                                              String yearMonth) {
        return Optional.ofNullable(selectOne(Wrappers.<WarehouseMonthlyCloseSheet>lambdaQuery()
                .eq(WarehouseMonthlyCloseSheet::getWarehouseId, warehouseId)
                .eq(WarehouseMonthlyCloseSheet::getYearMonth, yearMonth)
                .last("FOR UPDATE")));
    }
}
