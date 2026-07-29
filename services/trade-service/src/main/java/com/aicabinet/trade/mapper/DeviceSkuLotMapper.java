package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSkuLot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceSkuLotMapper extends BaseTradeMapper<DeviceSkuLot> {

    default List<DeviceSkuLot> findByDeviceIdAndSkuIdOrderByExpiryDateAsc(String deviceId, String skuId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .orderByAsc(DeviceSkuLot::getExpiryDate));
    }

    default List<DeviceSkuLot> findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc(
            String deviceId, String skuId, String slotId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getSlotId, slotId)
                .orderByAsc(DeviceSkuLot::getExpiryDate));
    }

    default List<DeviceSkuLot> findByDeviceId(String deviceId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery().eq(DeviceSkuLot::getDeviceId, deviceId));
    }

    default Optional<DeviceSkuLot> findByDeviceIdAndSkuIdAndBatchNo(
            String deviceId, String skuId, String batchNo) {
        List<DeviceSkuLot> rows = selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getBatchNo, batchNo)
                .orderByDesc(DeviceSkuLot::getUpdatedAt));
        return rows.stream().findFirst();
    }

    /** 同批次可能分多个货道存放；补货入库按货道精确匹配。 */
    default Optional<DeviceSkuLot> findByDeviceIdAndSkuIdAndBatchNoAndSlotId(
            String deviceId, String skuId, String batchNo, String slotId) {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getBatchNo, batchNo)
                .eq(DeviceSkuLot::getSlotId, slotId)));
    }

    int sumSellableQuantity(@Param("deviceId") String deviceId, @Param("skuId") String skuId);

    long countNearExpiry(@Param("today") LocalDate today, @Param("nearDate") LocalDate nearDate);

    long countExpiredWithStock(@Param("today") LocalDate today);

    default List<DeviceSkuLot> findByStatusInAndQuantityGreaterThan(List<String> statuses, int quantity) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .in(DeviceSkuLot::getStatus, statuses)
                .gt(DeviceSkuLot::getQuantity, quantity));
    }

    List<LinkedHashMap<String, Object>> _sumBookQtyBySlot(@Param("deviceId") String deviceId);

    default List<Object[]> sumBookQtyBySlot(String deviceId) {
        return ColumnMapRows.toObjectRows(_sumBookQtyBySlot(deviceId), 2);
    }
}
